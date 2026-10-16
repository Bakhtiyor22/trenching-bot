package com.trenching.bot.apiusage.domain

import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageTotals
import com.trenching.bot.apiusage.plus
import java.time.Instant

data class ApiCallAggregate(val provider: ApiProvider, val operation: String, val totals: ApiUsageTotals, val lastCallAt: Instant?)

data class ApiTaskAggregate(val task: ApiTask, val totals: ApiUsageTotals)

data class ApiSeriesAggregate(val bucketStart: Instant, val provider: ApiProvider, val requests: Long)

/** Usage of one window summed per call, per task and per bucket and provider; [plus] merges stored and unflushed usage. */
data class ApiUsageAggregates(
    val calls: List<ApiCallAggregate>,
    val tasks: List<ApiTaskAggregate>,
    val series: List<ApiSeriesAggregate>,
) {
    operator fun plus(other: ApiUsageAggregates) = ApiUsageAggregates(
        (calls + other.calls).groupBy { it.provider to it.operation }.map { (key, rows) ->
            ApiCallAggregate(key.first, key.second, sum(rows.map { it.totals }), rows.mapNotNull { it.lastCallAt }.maxOrNull())
        },
        (tasks + other.tasks).groupBy { it.task }.map { (task, rows) -> ApiTaskAggregate(task, sum(rows.map { it.totals })) },
        (series + other.series).groupBy { it.bucketStart to it.provider }.map { (key, rows) ->
            ApiSeriesAggregate(key.first, key.second, rows.sumOf { it.requests })
        },
    )

    private fun sum(totals: List<ApiUsageTotals>): ApiUsageTotals = totals.fold(ApiUsageTotals.ZERO, ApiUsageTotals::plus)

    companion object {
        val EMPTY = ApiUsageAggregates(emptyList(), emptyList(), emptyList())

        fun of(deltas: Map<ApiUsageBucketKey, ApiUsageDelta>): ApiUsageAggregates = EMPTY + ApiUsageAggregates(
            deltas.map { (key, delta) -> ApiCallAggregate(key.provider, key.operation, delta.totals, delta.lastCallAt) },
            deltas.map { (key, delta) -> ApiTaskAggregate(key.task, delta.totals) },
            deltas.map { (key, delta) -> ApiSeriesAggregate(key.bucketStart, key.provider, delta.requests) },
        )
    }
}
