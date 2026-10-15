package com.trenching.bot.chaincapture

import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Paces the requests every caller sends to one provider endpoint: at most [maxInFlight] at once, no two starts closer
 * than [minInterval], and no start at all while a rate-limit [holdStarts] is in force. One instance is shared by every
 * caller of an endpoint, so concurrent workers and separate operations pace together instead of each backing off alone.
 */
class ProviderRequestLimiter(
    maxInFlight: Int,
    minInterval: Duration,
    private val timing: CaptureTiming = SystemCaptureTiming,
) {
    private val permits = Semaphore(maxInFlight, true)
    private val intervalNanos = minInterval.toNanos()
    private val lock = ReentrantLock()
    private var nextStartNanos = timing.nanoTime()
    private var holdUntilNanos = nextStartNanos

    /** Runs [action], the one request, once a permit is free and its start slot has come. Waiting never passes the deadline of [budget]. */
    fun <T> withStart(budget: CaptureBudget, action: () -> T): T {
        acquirePermit(budget)
        try {
            awaitStartSlot(budget)
            return action()
        } finally { permits.release() }
    }

    /**
     * Keeps every caller from starting a request for [delay], as the provider asked after a rate limit. The hold is
     * recorded even when [delay] outlasts the deadline of [budget], which then stops with `DEADLINE` as a plain wait would.
     */
    fun holdStarts(delay: Duration, budget: CaptureBudget) {
        val now = timing.nanoTime()
        lock.withLock {
            val until = now + delay.toNanos()
            if (until - holdUntilNanos > 0) holdUntilNanos = until
        }
        if (delay.toNanos() >= budget.remainingNanos(now)) throw deadline(budget)
    }

    private fun acquirePermit(budget: CaptureBudget) {
        val remaining = maxOf(0L, budget.remainingNanos(timing.nanoTime()))
        val acquired = try { permits.tryAcquire(remaining, TimeUnit.NANOSECONDS) } catch (_: InterruptedException) { throw interrupted() }
        if (!acquired) throw deadline(budget)
    }

    private fun awaitStartSlot(budget: CaptureBudget) {
        while (true) {
            val now = timing.nanoTime()
            val wait = lock.withLock {
                val ready = if (holdUntilNanos - nextStartNanos > 0) holdUntilNanos else nextStartNanos
                val remaining = ready - now
                if (remaining <= 0) nextStartNanos = now + intervalNanos
                maxOf(0L, remaining)
            }
            if (wait == 0L) return
            if (wait >= budget.remainingNanos(now)) throw deadline(budget)
            try { timing.pause(Duration.ofNanos(wait)) } catch (_: InterruptedException) { throw interrupted() }
        }
    }

    private fun deadline(budget: CaptureBudget) = CaptureBudgetException("DEADLINE", budget.requestsUsed, budget.bytesUsed)

    private fun interrupted(): CaptureProviderException {
        Thread.currentThread().interrupt()
        return CaptureProviderException("INTERRUPTED")
    }

    companion object {
        fun of(settings: SolanaRpcSettings, timing: CaptureTiming = SystemCaptureTiming) =
            ProviderRequestLimiter(settings.maxConcurrentRequests, settings.minRequestInterval, timing)
    }
}
