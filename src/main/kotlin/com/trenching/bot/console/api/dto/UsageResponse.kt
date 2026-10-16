package com.trenching.bot.console.api.dto

import java.time.Instant

/** `GET /api/usage`: requests sent from [from] to [to] in [bucketSeconds] buckets, in the units [UsageTotalsResponse] names. */
data class UsageResponse(
    val from: Instant,
    val to: Instant,
    val bucketSeconds: Int,
    val recordingStartedAt: Instant?,
    val totals: UsageTotalsResponse,
    val byCall: List<UsageCallResponse>,
    val byTask: List<UsageTaskResponse>,
    val series: List<UsagePointResponse>,
    val pacing: List<UsagePacingResponse>,
)

/** [requests] are HTTP requests sent, retries included; [responseBytes] are response body bytes; [cacheHits] sent nothing. */
data class UsageTotalsResponse(
    val requests: Long,
    val failedRequests: Long,
    val rateLimitedResponses: Long,
    val responseBytes: Long,
    val cacheHits: Long,
)

data class UsageCallResponse(
    val provider: String,
    val operation: String,
    val purpose: String,
    val totals: UsageTotalsResponse,
    val sharePercent: String,
    val lastCallAt: Instant?,
)

data class UsageTaskResponse(val task: String, val totals: UsageTotalsResponse)

data class UsagePointResponse(val bucketStart: Instant, val provider: String, val requests: Long)

data class UsagePacingResponse(val provider: String, val minRequestIntervalMillis: Long, val maxConcurrentRequests: Int)
