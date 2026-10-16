package com.trenching.bot.apiusage.infrastructure.persistence

import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiTask
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import org.hibernate.annotations.ColumnDefault

/** One five-minute bucket of requests; rows are only ever added to by [ApiUsageBucketRepository.addDeltas]. */
@Entity
@Table(
    name = "api_usage_buckets",
    uniqueConstraints = [UniqueConstraint(name = "uq_api_usage_bucket_identity",
        columnNames = ["bucket_start", "provider", "operation", "task", "capture_scope"])],
    indexes = [
        Index(name = "idx_api_usage_buckets_start", columnList = "bucket_start"),
        Index(name = "idx_api_usage_buckets_capture", columnList = "capture_scope, bucket_start"),
    ],
)
class ApiUsageBucketEntity(
    @Id @Column(name = "id", nullable = false, length = 64) var id: String,
    @Column(name = "bucket_start", nullable = false) var bucketStart: Instant,
    @Enumerated(EnumType.STRING) @Column(name = "provider", nullable = false, length = 16) var provider: ApiProvider,
    @Column(name = "operation", nullable = false, length = 64) var operation: String,
    @Enumerated(EnumType.STRING) @Column(name = "task", nullable = false, length = 24) var task: ApiTask,
    @Column(name = "capture_scope", nullable = false, length = 80) var captureScope: String,
    @ColumnDefault("0") @Column(name = "requests", nullable = false) var requests: Long,
    @ColumnDefault("0") @Column(name = "failed_requests", nullable = false) var failedRequests: Long,
    @ColumnDefault("0") @Column(name = "rate_limited_responses", nullable = false) var rateLimitedResponses: Long,
    @ColumnDefault("0") @Column(name = "cache_hits", nullable = false) var cacheHits: Long,
    @ColumnDefault("0") @Column(name = "response_bytes", nullable = false) var responseBytes: Long,
    @Column(name = "last_call_at") var lastCallAt: Instant?,
)
