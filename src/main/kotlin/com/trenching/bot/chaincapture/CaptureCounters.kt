package com.trenching.bot.chaincapture

import java.util.concurrent.atomic.AtomicInteger

/** Thread-safe tallies of what one invocation experienced; request counts live on [CaptureBudget] itself. */
class CaptureCounters {
    private val hits = AtomicInteger()
    private val limited = AtomicInteger()
    private val retried = AtomicInteger()

    val cacheHits: Int get() = hits.get()
    val rateLimitedResponses: Int get() = limited.get()
    val retries: Int get() = retried.get()

    fun cacheHit() { hits.incrementAndGet() }
    fun rateLimited() { limited.incrementAndGet() }
    fun retry() { retried.incrementAndGet() }
}
