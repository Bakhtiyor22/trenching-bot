package com.trenching.bot.apiusage.application

import com.trenching.bot.apiusage.ApiCall
import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.apiusage.domain.ApiOperationNames
import com.trenching.bot.apiusage.domain.ApiUsageBucketKey
import com.trenching.bot.apiusage.domain.ApiUsageBuckets
import com.trenching.bot.apiusage.domain.ApiUsageDelta
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.slf4j.LoggerFactory

/**
 * The process's [ApiUsageRecorder]: adds each call to its bucket's in-memory delta and never blocks on the database.
 * A drain holds the write lock until its deltas are stored, and a reader holds the read lock, so a report never sees
 * counts both in memory and in the database, or in neither.
 */
class ApiUsageAccumulator(private val clock: Clock) : ApiUsageRecorder {
    private val log = LoggerFactory.getLogger(ApiUsageAccumulator::class.java)
    private val pending = ConcurrentHashMap<ApiUsageBucketKey, ApiUsageDelta>()
    private val flushLock = ReentrantReadWriteLock()

    override fun record(call: ApiCall) {
        val operation = if (ApiOperationNames.isRecordable(call.operation)) call.operation else {
            log.warn("api_usage operation_rejected provider={} task={} recordedAs={}", call.provider, call.context.task, ApiOperationNames.OTHER)
            ApiOperationNames.OTHER
        }
        val now = clock.instant()
        val key = ApiUsageBucketKey(ApiUsageBuckets.startOf(now), call.provider, operation, call.context.task,
            ApiUsageBuckets.scopeOf(call.context.captureId))
        pending.merge(key, ApiUsageDelta.of(call.outcome, call.responseBytes, now), ApiUsageDelta::plus)
    }

    /** Hands every pending delta to [write] once and returns how many buckets it wrote; a failed write puts them back. */
    fun drainTo(write: (Map<ApiUsageBucketKey, ApiUsageDelta>) -> Unit): Int = flushLock.write {
        val drained = HashMap<ApiUsageBucketKey, ApiUsageDelta>()
        pending.keys.forEach { key -> pending.remove(key)?.let { drained[key] = it } }
        if (drained.isEmpty()) return 0
        try {
            write(drained)
        } catch (exception: RuntimeException) {
            drained.forEach { (key, delta) -> pending.merge(key, delta, ApiUsageDelta::plus) }
            throw exception
        }
        drained.size
    }

    /** Runs [read] with a snapshot of the deltas not yet stored, while no drain can store them. */
    fun <T> withPending(read: (Map<ApiUsageBucketKey, ApiUsageDelta>) -> T): T = flushLock.read { read(HashMap(pending)) }
}
