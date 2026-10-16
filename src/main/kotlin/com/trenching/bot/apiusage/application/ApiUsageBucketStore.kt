package com.trenching.bot.apiusage.application

import com.trenching.bot.apiusage.domain.ApiUsageBucketKey
import com.trenching.bot.apiusage.domain.ApiUsageBuckets
import com.trenching.bot.apiusage.domain.ApiUsageDelta
import com.trenching.bot.apiusage.infrastructure.persistence.ApiUsageBucketRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ApiUsageBucketStore(private val repository: ApiUsageBucketRepository) {
    /** Adds every delta in one transaction, in id order so two concurrent flushes lock rows in the same order. */
    @Transactional
    fun add(deltas: Map<ApiUsageBucketKey, ApiUsageDelta>) {
        deltas.entries.map { ApiUsageBuckets.id(it.key) to it }.sortedBy { it.first }.forEach { (id, entry) ->
            val (key, delta) = entry
            val totals = delta.totals
            repository.addDeltas(id, key.bucketStart, key.provider.name, key.operation, key.task.name, key.captureScope,
                totals.requests, totals.failedRequests, totals.rateLimitedResponses, totals.cacheHits, totals.responseBytes, delta.lastCallAt)
        }
    }
}
