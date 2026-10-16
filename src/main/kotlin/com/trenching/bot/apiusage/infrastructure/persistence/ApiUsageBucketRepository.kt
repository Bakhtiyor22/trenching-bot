package com.trenching.bot.apiusage.infrastructure.persistence

import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** Reads are aggregates over a `bucket_start` range; a null capture scope means every scope. */
interface ApiUsageBucketRepository : JpaRepository<ApiUsageBucketEntity, String> {
    /** Adds the deltas to the bucket, creating it on first use, so concurrent and repeated flushes of distinct deltas converge. */
    @Modifying
    @Query(nativeQuery = true, value = """
        insert into api_usage_buckets (id, bucket_start, provider, operation, task, capture_scope, requests, failed_requests,
            rate_limited_responses, cache_hits, response_bytes, last_call_at)
        values (:id, :bucketStart, :provider, :operation, :task, :captureScope, :requests, :failedRequests,
            :rateLimitedResponses, :cacheHits, :responseBytes, :lastCallAt)
        on conflict (id) do update set
            requests = api_usage_buckets.requests + excluded.requests,
            failed_requests = api_usage_buckets.failed_requests + excluded.failed_requests,
            rate_limited_responses = api_usage_buckets.rate_limited_responses + excluded.rate_limited_responses,
            cache_hits = api_usage_buckets.cache_hits + excluded.cache_hits,
            response_bytes = api_usage_buckets.response_bytes + excluded.response_bytes,
            last_call_at = greatest(api_usage_buckets.last_call_at, excluded.last_call_at)
    """)
    fun addDeltas(
        @Param("id") id: String,
        @Param("bucketStart") bucketStart: Instant,
        @Param("provider") provider: String,
        @Param("operation") operation: String,
        @Param("task") task: String,
        @Param("captureScope") captureScope: String,
        @Param("requests") requests: Long,
        @Param("failedRequests") failedRequests: Long,
        @Param("rateLimitedResponses") rateLimitedResponses: Long,
        @Param("cacheHits") cacheHits: Long,
        @Param("responseBytes") responseBytes: Long,
        @Param("lastCallAt") lastCallAt: Instant?,
    ): Int

    @Query("select min(b.bucketStart) from ApiUsageBucketEntity b")
    fun findOldestBucketStart(): Instant?

    @Query("""
        select b.provider as provider, b.operation as operation, sum(b.requests) as requests, sum(b.failedRequests) as failedRequests,
            sum(b.rateLimitedResponses) as rateLimitedResponses, sum(b.responseBytes) as responseBytes, sum(b.cacheHits) as cacheHits,
            max(b.lastCallAt) as lastCallAt
        from ApiUsageBucketEntity b
        where b.bucketStart >= :from and b.bucketStart <= :to and (:captureScope is null or b.captureScope = :captureScope)
        group by b.provider, b.operation
    """)
    fun sumByCall(@Param("from") from: Instant, @Param("to") to: Instant, @Param("captureScope") captureScope: String?): List<ApiCallUsageRow>

    @Query("""
        select b.task as task, sum(b.requests) as requests, sum(b.failedRequests) as failedRequests,
            sum(b.rateLimitedResponses) as rateLimitedResponses, sum(b.responseBytes) as responseBytes, sum(b.cacheHits) as cacheHits
        from ApiUsageBucketEntity b
        where b.bucketStart >= :from and b.bucketStart <= :to and (:captureScope is null or b.captureScope = :captureScope)
        group by b.task
    """)
    fun sumByTask(@Param("from") from: Instant, @Param("to") to: Instant, @Param("captureScope") captureScope: String?): List<ApiTaskUsageRow>

    @Query("""
        select b.bucketStart as bucketStart, b.provider as provider, sum(b.requests) as requests
        from ApiUsageBucketEntity b
        where b.bucketStart >= :from and b.bucketStart <= :to and (:captureScope is null or b.captureScope = :captureScope)
        group by b.bucketStart, b.provider
    """)
    fun sumByBucket(@Param("from") from: Instant, @Param("to") to: Instant, @Param("captureScope") captureScope: String?): List<ApiSeriesUsageRow>
}
