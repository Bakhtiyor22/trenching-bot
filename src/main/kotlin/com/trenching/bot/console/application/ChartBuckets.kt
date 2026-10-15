package com.trenching.bot.console.application

import com.trenching.bot.console.domain.CandleView
import com.trenching.bot.console.domain.Ohlc
import java.math.BigDecimal
import java.time.Instant

/** Epoch-aligned time buckets of a fixed size ladder, and the candles built in them. */
object ChartBuckets {
    /** The bucket sizes in seconds: 1s, 5s, 15s, 30s, 1m, 5m, 15m, 1h, 4h, 1d. */
    val LADDER_SECONDS: List<Int> = listOf(1, 5, 15, 30, 60, 300, 900, 3_600, 14_400, 86_400)

    /** The smallest ladder size that covers `from..to` in at most [maxBuckets] buckets, else the largest. */
    fun bucketSeconds(from: Instant?, to: Instant?, maxBuckets: Int): Int {
        if (from == null || to == null) return LADDER_SECONDS.first()
        return LADDER_SECONDS.firstOrNull { bucketCount(from, to, it) <= maxBuckets } ?: LADDER_SECONDS.last()
    }

    fun bucketStart(time: Instant, bucketSeconds: Int): Instant =
        Instant.ofEpochSecond(Math.floorDiv(time.epochSecond, bucketSeconds.toLong()) * bucketSeconds)

    /** One candle per bucket with data, in time order; [points] must already be ordered by time, slot and id. */
    fun candles(points: List<ChartPoint>, bucketSeconds: Int): List<CandleView> =
        points.groupBy { bucketStart(it.observation.observedTime, bucketSeconds) }.map { (start, bucket) ->
            CandleView(
                bucketStart = start,
                observationCount = bucket.size,
                price = ohlc(bucket.map { it.observation.price }),
                mcap = bucket.mapNotNull { it.mcap }.takeIf { it.isNotEmpty() }?.let(::ohlc),
                mcapMissingPoints = bucket.count { it.valuation == PointValuation.MissingRate },
                mcapUnsupportedPoints = bucket.count { it.valuation is PointValuation.Unsupported },
            )
        }

    private fun bucketCount(from: Instant, to: Instant, bucketSeconds: Int): Long =
        Math.floorDiv(to.epochSecond, bucketSeconds.toLong()) - Math.floorDiv(from.epochSecond, bucketSeconds.toLong()) + 1

    /** Open and close are the first and last of the non-empty [values] in time order; high and low the extremes. */
    private fun ohlc(values: List<BigDecimal>): Ohlc = Ohlc(values.first(), values.max(), values.min(), values.last())
}
