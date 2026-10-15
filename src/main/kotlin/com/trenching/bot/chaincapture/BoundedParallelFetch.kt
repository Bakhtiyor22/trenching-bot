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
 * with the error so the caller can checkpoint them before stopping. The same happens when `stopRequested` turns true,
 * and when it throws, which is a fatal failure. A caller that is interrupted gets an interruption failure instead: the
 * workers are interrupted and awaited for a bounded time so the result is a settled snapshot, and the interrupt flag is
 * restored. An `Error` from any fetch is rethrown to the caller in preference to every exception, whatever its index.
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
        val failures = ConcurrentHashMap<Int, Exception>()
        val errors = ConcurrentHashMap<Int, Error>()
        val fatal = AtomicBoolean()
        val slots = Semaphore(concurrency)
        val threads = AtomicInteger()
        val pool = Executors.newFixedThreadPool(minOf(concurrency, maxOf(1, items.size))) { task ->
            Thread(task, "$THREAD_PREFIX${threads.incrementAndGet()}").apply { isDaemon = true }
        }
        var stopped = false
        var stopFailure: Exception? = null
        var interrupted = false
        fun stopNow(): Boolean = try { stopRequested().also { if (it) stopped = true } } catch (failure: Exception) {
            stopFailure = failure
            true
        }
        try {
            for ((index, item) in items.withIndex()) {
                if (fatal.get() || stopNow()) break
                try { slots.acquire() } catch (_: InterruptedException) {
                    interrupted = true
                    break
                }
                if (fatal.get() || stopNow()) {
                    slots.release()
                    break
                }
                pool.execute {
                    try { results[index] = fetchOne(item) } catch (failure: Exception) {
                        failures[index] = failure
                        fatal.set(true)
                    // An Error must reach the caller, so the worker keeps it instead of dying silently on its own thread.
                    } catch (error: Error) {
                        errors[index] = error
                        fatal.set(true)
                    } finally { slots.release() }
                }
            }
        } finally { interrupted = shutDown(pool, interrupted) || interrupted }
        errors.entries.minByOrNull { it.key }?.let { throw it.value }
        return ParallelFetchResult(
            completed = results.entries.sortedBy { it.key }.map { FetchedItem(it.key, items[it.key], it.value) },
            failure = if (interrupted) CaptureProviderException("INTERRUPTED") else failures.entries.minByOrNull { it.key }?.value ?: stopFailure,
            stopped = stopped,
            measurements = Counts.of(budget).since(before, Duration.ofNanos(timing.nanoTime() - startedNanos)),
            itemCount = items.size,
        )
    }

    /** Waits for the workers; if the caller is or gets interrupted, interrupts them and waits a bounded time. Returns whether the caller was interrupted. */
    private fun shutDown(pool: ExecutorService, alreadyInterrupted: Boolean): Boolean {
        var interrupted = alreadyInterrupted
        if (!interrupted) {
            pool.shutdown()
            try { pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) {
            pool.shutdownNow()
            try { pool.awaitTermination(INTERRUPT_GRACE.toNanos(), TimeUnit.NANOSECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            Thread.currentThread().interrupt()
        }
        return interrupted
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
        private val INTERRUPT_GRACE: Duration = Duration.ofSeconds(5)
    }
}
