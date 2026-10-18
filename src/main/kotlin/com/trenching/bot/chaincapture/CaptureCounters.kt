package com.trenching.bot.chaincapture

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Thread-safe tallies of what one invocation experienced; request counts live on [CaptureBudget] itself. */
class CaptureCounters {
    private val hits = AtomicInteger()
    private val limited = AtomicInteger()
    private val retried = AtomicInteger()
    private val retryAfter = AtomicReference<Duration?>(null)

    val cacheHits: Int get() = hits.get()
    val rateLimitedResponses: Int get() = limited.get()
    val retries: Int get() = retried.get()

    /** The `Retry-After` of the latest rate limited reply that carried one. */
    val lastRetryAfter: Duration? get() = retryAfter.get()

    fun cacheHit() { hits.incrementAndGet() }

    fun rateLimited(retryAfter: Duration? = null) {
        limited.incrementAndGet()
        retryAfter?.let(this.retryAfter::set)
    }

    fun retry() { retried.incrementAndGet() }
}
