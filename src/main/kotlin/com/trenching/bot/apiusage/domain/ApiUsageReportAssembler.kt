package com.trenching.bot.apiusage.domain

import com.trenching.bot.apiusage.ApiCallUsage
import com.trenching.bot.apiusage.ApiPacing
import com.trenching.bot.apiusage.ApiPurpose
import com.trenching.bot.apiusage.ApiTaskUsage
import com.trenching.bot.apiusage.ApiUsagePoint
import com.trenching.bot.apiusage.ApiUsageReport
import com.trenching.bot.apiusage.ApiUsageTotals
import com.trenching.bot.apiusage.plus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Orders a window's usage deterministically: calls by provider then most requests, tasks by most requests, series by time. */
object ApiUsageReportAssembler {
    private const val SHARE_SCALE = 1
    private const val ZERO_SHARE = "0"
    private val HUNDRED = BigDecimal(100)

    fun assemble(window: ApiUsageWindow, usage: ApiUsageAggregates, recordingStartedAt: Instant?, pacing: List<ApiPacing>): ApiUsageReport {
        val totals = usage.calls.map { it.totals }.fold(ApiUsageTotals.ZERO, ApiUsageTotals::plus)
        val calls = usage.calls
            .sortedWith(compareBy<ApiCallAggregate> { it.provider }.thenByDescending { it.totals.requests }.thenBy { it.operation })
            .map {
                ApiCallUsage(it.provider, it.operation, ApiPurpose.of(it.provider, it.operation), it.totals,
                    share(it.totals.requests, totals.requests), it.lastCallAt)
            }
        val tasks = usage.tasks
            .sortedWith(compareByDescending<ApiTaskAggregate> { it.totals.requests }.thenBy { it.task })
            .map { ApiTaskUsage(it.task, it.totals) }
        val series = usage.series
            .sortedWith(compareBy<ApiSeriesAggregate> { it.bucketStart }.thenBy { it.provider })
            .map { ApiUsagePoint(it.bucketStart, it.provider, it.requests) }
        return ApiUsageReport(window.from, window.to, ApiUsageBuckets.BUCKET_SECONDS, recordingStartedAt, totals, calls, tasks, series, pacing)
    }

    /** [part] of [total] in percent at scale 1, rounded half-even; with no requests at all every share is "0". */
    fun share(part: Long, total: Long): String {
        if (total == 0L) return ZERO_SHARE
        return BigDecimal(part).multiply(HUNDRED).divide(BigDecimal(total), SHARE_SCALE, RoundingMode.HALF_EVEN).toPlainString()
    }
}
