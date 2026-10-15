package com.trenching.bot.chaincapture

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fetches the details of an ordered list with at most `concurrency` fetches running, started in input order. A fetch
 * that throws is fatal to the run: nothing new starts, the fetches already running finish, and their results come back
 * with the error so the caller can checkpoint them before stopping. The same happens when `stopRequested` turns true.
 * Provider pacing is the limiter's job; this only bounds the workers of one phase.
 */
class BoundedParallelFetch(private val timing: CaptureTiming = SystemCaptureTiming) {
    fun <I, R : Any> fetch(
        items: List<I>,
        concurrency: Int,
        budget: CaptureBudget,
        stopRequested: () -> Boolean,
        fetchOne: (I) -> R,
    ): ParallelFetchResult<I, R> {
        require(concurrency >= 1) { "Concurrency must be at least 1" }
        val before = Counts.of(budget)
        val startedNanos = timing.nanoTime()
        val results = ConcurrentHashMap<Int, R>()
        val failures = ConcurrentHashMap<Int, Throwable>()
        val fatal = AtomicBoolean()
        val slots = Semaphore(concurrency)
        val threads = AtomicInteger()
        val pool = Executors.newFixedThreadPool(minOf(concurrency, maxOf(1, items.size))) { task ->
            Thread(task, "$THREAD_PREFIX${threads.incrementAndGet()}").apply { isDaemon = true }
        }
        var stopped = false
        var interruption: Exception? = null
        try {
            for ((index, item) in items.withIndex()) {
                if (fatal.get() || stopRequested().also { stopped = it }) break
                try { slots.acquire() } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    interruption = CaptureProviderException("INTERRUPTED")
                    break
                }
                if (fatal.get() || stopRequested().also { stopped = it }) {
                    slots.release()
                    break
                }
                pool.execute {
                    try { results[index] = fetchOne(item) } catch (failure: Throwable) {
                        failures[index] = failure
                        fatal.set(true)
                    } finally { slots.release() }
                }
            }
        } finally { shutDown(pool) }
        val firstFailure = failures.entries.minByOrNull { it.key }?.value
        if (firstFailure != null && firstFailure !is Exception) throw firstFailure
        return ParallelFetchResult(
            completed = results.entries.sortedBy { it.key }.map { FetchedItem(it.key, items[it.key], it.value) },
            failure = firstFailure as Exception? ?: interruption,
            stopped = stopped,
            measurements = Counts.of(budget).since(before, Duration.ofNanos(timing.nanoTime() - startedNanos)),
            itemCount = items.size,
        )
    }

    private fun shutDown(pool: ExecutorService) {
        pool.shutdown()
        try { pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) } catch (_: InterruptedException) {
            pool.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private data class Counts(val requests: Int, val cacheHits: Int, val rateLimited: Int, val retries: Int) {
        fun since(start: Counts, elapsed: Duration) = CaptureMeasurements(
            requestsSent = requests - start.requests, cacheHits = cacheHits - start.cacheHits,
            rateLimitedResponses = rateLimited - start.rateLimited, retries = retries - start.retries, elapsed = elapsed,
        )

        companion object {
            fun of(budget: CaptureBudget) = Counts(budget.requestsUsed, budget.counters.cacheHits, budget.counters.rateLimitedResponses, budget.counters.retries)
        }
    }

    companion object {
        const val THREAD_PREFIX = "capture-fetch-"
    }
}
