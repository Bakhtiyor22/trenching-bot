package com.trenching.bot.console

import com.trenching.bot.console.ChartFixtures.at
import com.trenching.bot.console.ChartFixtures.observation
import com.trenching.bot.console.application.ChartBuckets
import com.trenching.bot.console.application.ChartPoint
import com.trenching.bot.console.application.PointValuation
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ChartBucketsTest {
    private val start = at("2026-10-01T00:00:00Z")

    @Test
    fun `the smallest ladder size that fits the range in the bucket cap is chosen`() {
        assertEquals(1, ChartBuckets.bucketSeconds(start, start.plusSeconds(100), 300))
        assertEquals(5, ChartBuckets.bucketSeconds(start, start.plusSeconds(1_000), 300))
        assertEquals(60, ChartBuckets.bucketSeconds(start, start.plusSeconds(3_600 * 4), 300))
        assertEquals(900, ChartBuckets.bucketSeconds(start, start.plusSeconds(86_400 * 10), 2_000))
    }

    @Test
    fun `a range that straddles a boundary counts the extra bucket`() {
        val from = at("2026-10-01T00:00:59Z")
        val to = at("2026-10-01T00:01:01Z")

        assertEquals(1, ChartBuckets.bucketSeconds(from, from, 1))
        assertEquals(5, ChartBuckets.bucketSeconds(from, to, 2))
        assertEquals(300, ChartBuckets.bucketSeconds(from, to, 1))
    }

    @Test
    fun `no range or an impossible cap falls back to the finest and the coarsest size`() {
        assertEquals(1, ChartBuckets.bucketSeconds(null, null, 300))
        assertEquals(86_400, ChartBuckets.bucketSeconds(start, start.plusSeconds(86_400L * 4_000), 2))
    }

    @Test
    fun `a bucket starts at the epoch aligned multiple of its size`() {
        assertEquals(at("2026-10-01T00:05:00Z"), ChartBuckets.bucketStart(at("2026-10-01T00:07:42.5Z"), 300))
        assertEquals(at("2026-10-01T00:07:42Z"), ChartBuckets.bucketStart(at("2026-10-01T00:07:42.999Z"), 1))
        assertEquals(at("2026-10-01T00:00:00Z"), ChartBuckets.bucketStart(at("2026-10-01T23:59:59Z"), 86_400))
    }

    @Test
    fun `candles take open and close from time order and high and low from the extremes`() {
        val points = listOf(
            point("a", "2026-10-01T00:00:01Z", "3"),
            point("b", "2026-10-01T00:00:02Z", "9"),
            point("c", "2026-10-01T00:00:03Z", "1"),
            point("d", "2026-10-01T00:00:04Z", "4"),
        )

        val candle = ChartBuckets.candles(points, 60).single()

        assertEquals(start, candle.bucketStart)
        assertEquals(4, candle.observationCount)
        assertEquals(listOf("3", "9", "1", "4"), listOf(candle.price.open, candle.price.high, candle.price.low, candle.price.close).map { it.toPlainString() })
        assertNull(candle.mcap)
        assertEquals(0, candle.mcapMissingPoints)
    }

    @Test
    fun `only buckets with data are returned and gaps stay absent`() {
        val points = listOf(point("a", "2026-10-01T00:00:10Z", "1"), point("b", "2026-10-01T00:10:10Z", "2"))

        assertEquals(listOf(start, at("2026-10-01T00:10:00Z")), ChartBuckets.candles(points, 60).map { it.bucketStart })
    }

    @Test
    fun `mcap candles aggregate the converted points so a rate change inside the bucket moves the high`() {
        val points = listOf(
            point("a", "2026-10-01T00:00:30Z", "0.00000002", PointValuation.Valued(BigDecimal("4000.000000000000"))),
            point("b", "2026-10-01T00:01:30Z", "0.00000002", PointValuation.Valued(BigDecimal("3000.000000000000"))),
            point("c", "2026-10-01T00:01:40Z", "0.00000001", PointValuation.Valued(BigDecimal("1500.000000000000"))),
        )

        val mcap = assertNotNull(ChartBuckets.candles(points, 300).single().mcap)

        assertEquals(listOf("4000.000000000000", "4000.000000000000", "1500.000000000000", "1500.000000000000"),
            listOf(mcap.open, mcap.high, mcap.low, mcap.close).map { it.toPlainString() })
    }

    @Test
    fun `a point without a rate is counted and a bucket without a valued point has no mcap`() {
        val points = listOf(
            point("a", "2026-10-01T00:00:01Z", "1", PointValuation.MissingRate),
            point("b", "2026-10-01T00:00:02Z", "2", PointValuation.MissingRate),
            point("c", "2026-10-01T00:02:01Z", "3", PointValuation.Valued(BigDecimal("5.000000000000"))),
            point("d", "2026-10-01T00:02:02Z", "4", PointValuation.MissingRate),
        )

        val candles = ChartBuckets.candles(points, 60)

        assertNull(candles[0].mcap)
        assertEquals(2, candles[0].mcapMissingPoints)
        assertEquals("5.000000000000", assertNotNull(candles[1].mcap).close.toPlainString())
        assertEquals(1, candles[1].mcapMissingPoints)
    }

    private fun point(id: String, time: String, price: String, valuation: PointValuation = PointValuation.RatesUnavailable): ChartPoint =
        ChartPoint(observation(id, Instant.parse(time), price), valuation)
}
