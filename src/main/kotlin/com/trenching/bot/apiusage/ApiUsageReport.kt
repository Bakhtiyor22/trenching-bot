package com.trenching.bot.apiusage

import java.time.Instant

/** Counts in the units every report names: requests sent (retries included) and response body bytes received. */
data class ApiUsageTotals(
    val requests: Long,
    val failedRequests: Long,
    val rateLimitedResponses: Long,
    val responseBytes: Long,
    val cacheHits: Long,
) {
    companion object {
        val ZERO = ApiUsageTotals(0, 0, 0, 0, 0)
    }
}

/** An extension rather than a member, so mappers do not take it for a fluent setter. */
operator fun ApiUsageTotals.plus(other: ApiUsageTotals) = ApiUsageTotals(
    Math.addExact(requests, other.requests),
    Math.addExact(failedRequests, other.failedRequests),
    Math.addExact(rateLimitedResponses, other.rateLimitedResponses),
    Math.addExact(responseBytes, other.responseBytes),
    Math.addExact(cacheHits, other.cacheHits),
)

/** [sharePercent] is this call's share of all requests in the report, scale 1, `HALF_EVEN`. */
data class ApiCallUsage(
    val provider: ApiProvider,
    val operation: String,
    val purpose: ApiPurpose,
    val totals: ApiUsageTotals,
    val sharePercent: String,
    val lastCallAt: Instant?,
)

data class ApiTaskUsage(val task: ApiTask, val totals: ApiUsageTotals)

data class ApiUsagePoint(val bucketStart: Instant, val provider: ApiProvider, val requests: Long)

/** Request pacing a provider is held to, from non-secret settings only. */
data class ApiPacing(val provider: ApiProvider, val minRequestIntervalMillis: Long, val maxConcurrentRequests: Int)

/** Usage from [from] to [to] in [bucketSeconds] buckets; [recordingStartedAt] is the oldest bucket's start, null before any call. */
data class ApiUsageReport(
    val from: Instant,
    val to: Instant,
    val bucketSeconds: Int,
    val recordingStartedAt: Instant?,
    val totals: ApiUsageTotals,
    val byCall: List<ApiCallUsage>,
    val byTask: List<ApiTaskUsage>,
    val series: List<ApiUsagePoint>,
    val pacing: List<ApiPacing>,
)
