package com.trenching.bot.console

import com.trenching.bot.console.ChartFixtures.SIGNATURE
import com.trenching.bot.console.ChartFixtures.TRACKED
import com.trenching.bot.console.ChartFixtures.TRADER_A
import com.trenching.bot.console.ChartFixtures.TRADER_B
import com.trenching.bot.console.ChartFixtures.at
import com.trenching.bot.console.ChartFixtures.tradeObservation
import com.trenching.bot.console.application.ChartMarkers
import com.trenching.bot.console.application.ChartPoint
import com.trenching.bot.console.application.ChartPosition
import com.trenching.bot.console.application.PointValuation
import com.trenching.bot.console.domain.MarkerKind
import com.trenching.bot.console.domain.MarkerMode
import com.trenching.bot.console.domain.MarkersUnavailableReason
import com.trenching.bot.console.domain.MarkersView
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.TradeSide
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ChartMarkersTest {
    private val start = at("2026-10-01T00:00:00Z")

    private val observations = listOf(
        trade("t1", 5, TRACKED), trade("a1", 10, TRADER_A), trade("a2", 20, TRADER_A, TradeSide.SELL), trade("b1", 30, TRADER_B),
        trade("t2", 70, TRACKED, TradeSide.SELL), trade("a3", 80, TRADER_A), trade("b2", 130, TRADER_B), trade("t3", 140, TRACKED),
    )
    private val points = observations.map { ChartPoint(it, PointValuation.Valued(BigDecimal("12.000000000000"))) }

    @Test
    fun `at or under the limit every matching trade is an item`() {
        val markers = select(limit = 8)

        assertEquals(MarkerMode.ITEMS, markers.mode)
        assertEquals(8, markers.total)
        assertEquals(observations.map { it.id }, markers.items.map { it.observationId })
        assertTrue(markers.clusters.isEmpty())
        assertNull(markers.nextCursor)
    }

    @Test
    fun `over the limit the tracked wallet's trades stay items and the rest are counted per bucket`() {
        val markers = select(limit = 7)

        assertEquals(MarkerMode.CLUSTERED, markers.mode)
        assertEquals(listOf("t1", "t2", "t3"), markers.items.map { it.observationId })
        assertEquals(5, markers.clusters.sumOf { it.count })
        assertEquals(markers.total, markers.items.size + markers.clusters.sumOf { it.count })
        assertEquals(listOf(start, start.plusSeconds(60), start.plusSeconds(120)), markers.clusters.map { it.bucketStart })
        val first = markers.clusters.first()
        assertEquals(listOf(3, 2, 1), listOf(first.count, first.buyCount, first.sellCount))
        assertEquals(listOf(10L, 30L), listOf(first.firstSlot, first.lastSlot).map { it - start.epochSecond })
        assertEquals(listOf(start.plusSeconds(10), start.plusSeconds(30)), listOf(first.firstTime, first.lastTime))
    }

    @Test
    fun `the tracked flag is exactly the tracked wallet`() {
        val markers = select(limit = 8)

        assertEquals(setOf("t1", "t2", "t3"), markers.items.filter { it.tracked }.map { it.observationId }.toSet())
        assertTrue(markers.items.filter { !it.tracked }.none { it.trader == TRACKED })
    }

    @Test
    fun `without a tracked wallet nothing is flagged and a clustered response has no items`() {
        val markers = select(limit = 7, tracked = null)

        assertTrue(markers.items.isEmpty())
        assertEquals(8, markers.clusters.sumOf { it.count })
        assertFalse(select(limit = 8, tracked = null).items.any { it.tracked })
    }

    @Test
    fun `a wallet filter keeps those wallets and always the tracked one`() {
        val markers = select(limit = 100, wallets = setOf(TRADER_B))

        assertEquals(listOf("t1", "b1", "t2", "b2", "t3"), markers.items.map { it.observationId })
        assertEquals(5, markers.total)
    }

    @Test
    fun `a clustered response pages the tracked wallet's trades and only the first page has clusters`() {
        val first = select(limit = 2, bucketSeconds = 60).also { assertEquals(MarkerMode.CLUSTERED, it.mode) }
        val cursor = assertNotNull(first.nextCursor)
        val second = select(limit = 2, after = ChartPosition.decode(cursor))

        assertEquals(listOf("t1", "t2"), first.items.map { it.observationId })
        assertTrue(first.clusters.isNotEmpty())
        assertEquals(listOf("t3"), second.items.map { it.observationId })
        assertTrue(second.clusters.isEmpty())
        assertNull(second.nextCursor)
        assertEquals(first.total, second.total)
        assertEquals(first.total, first.items.size + second.items.size + first.clusters.sumOf { it.count })
    }

    @Test
    fun `an item carries the trade, the valuation and the transaction signature`() {
        val item = select(limit = 8).items.first()

        assertEquals(MarkerKind.OBSERVED, item.kind)
        assertEquals("t1", item.observationId)
        assertEquals(TRACKED, item.trader)
        assertEquals(TradeSide.BUY, item.side)
        assertEquals("1000000000", item.solLamports.toString())
        assertEquals("50000000000", item.tokenRaw.toString())
        assertEquals(BigDecimal("12.000000000000"), item.mcap)
        assertEquals(start.plusSeconds(5), item.time)
        assertEquals(SIGNATURE, item.signature)
    }

    @Test
    fun `points without a trade are skipped`() {
        val plain = ChartPoint(ChartFixtures.observation("p", start, "1"), PointValuation.MissingRate)

        assertEquals(8, ChartMarkers.select(listOf(plain) + points, TRACKED, emptySet(), 100, null, 60).total)
    }

    @Test
    fun `the wallet's unobserved trades join the items in chart order and the total`() {
        val selected = select(limit = 7)
        val firstBuy = selected.items.first().copy(observationId = "first-buy", time = start, slot = start.epochSecond)

        val markers = ChartMarkers.including(selected, listOf(firstBuy))

        assertEquals(listOf("first-buy", "t1", "t2", "t3"), markers.items.map { it.observationId })
        assertEquals(selected.total + 1, markers.total)
        assertEquals(markers.total, markers.items.size + markers.clusters.sumOf { it.count })
        assertEquals(selected.copy(items = markers.items, total = markers.total), markers)
    }

    @Test
    fun `unrecorded markers say why`() {
        val markers = ChartMarkers.notRecorded()

        assertFalse(markers.available)
        assertEquals(MarkersUnavailableReason.NOT_RECORDED_BEFORE_SCHEMA_V4, markers.unavailableReason)
        assertEquals(0, markers.total)
        assertTrue(markers.items.isEmpty() && markers.clusters.isEmpty())
    }

    private fun select(
        limit: Int,
        tracked: String? = TRACKED,
        wallets: Set<String> = emptySet(),
        after: ChartPosition? = null,
        bucketSeconds: Int = 60,
    ): MarkersView = ChartMarkers.select(points, tracked, wallets, limit, after, bucketSeconds)

    private fun trade(id: String, offsetSeconds: Long, trader: String, side: TradeSide = TradeSide.BUY): MarketObservation =
        tradeObservation(id, start.plusSeconds(offsetSeconds), trader, side)
}
