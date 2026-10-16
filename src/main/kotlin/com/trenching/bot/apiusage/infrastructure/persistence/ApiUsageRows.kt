package com.trenching.bot.apiusage.infrastructure.persistence

import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiTask
import java.time.Instant

interface ApiUsageTotalsRow {
    val requests: Long
    val failedRequests: Long
    val rateLimitedResponses: Long
    val responseBytes: Long
    val cacheHits: Long
}

interface ApiCallUsageRow : ApiUsageTotalsRow {
    val provider: ApiProvider
    val operation: String
    val lastCallAt: Instant?
}

interface ApiTaskUsageRow : ApiUsageTotalsRow {
    val task: ApiTask
}

interface ApiSeriesUsageRow {
    val bucketStart: Instant
    val provider: ApiProvider
    val requests: Long
}
