package com.trenching.bot.chaincapture

import java.time.Duration

/** What one fetch phase cost: provider requests actually sent, transactions served from the cache, rate limits and retries. */
data class CaptureMeasurements(
    val requestsSent: Int,
    val cacheHits: Int,
    val rateLimitedResponses: Int,
    val retries: Int,
    val elapsed: Duration,
)
