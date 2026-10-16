package com.trenching.bot.apiusage.domain

import com.trenching.bot.apiusage.ApiUsagePeriod
import java.time.Instant

/** Buckets starting from [from] through [to], of one capture or, when [captureId] is null, of everything. */
data class ApiUsageWindow(val from: Instant, val to: Instant, val captureId: String?) {
    fun includes(key: ApiUsageBucketKey): Boolean =
        key.bucketStart >= from && key.bucketStart <= to && (captureId == null || key.captureScope == captureId)

    companion object {
        /** [period] ending at [now]. An open period starts at [oldestBucket], or at the current bucket when nothing was recorded. */
        fun of(period: ApiUsagePeriod, captureId: String?, now: Instant, oldestBucket: Instant?): ApiUsageWindow {
            val from = period.duration?.let { ApiUsageBuckets.startOf(now.minus(it)) } ?: oldestBucket ?: ApiUsageBuckets.startOf(now)
            return ApiUsageWindow(from, now, captureId)
        }
    }
}
