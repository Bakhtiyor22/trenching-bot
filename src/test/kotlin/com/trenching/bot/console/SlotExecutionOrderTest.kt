package com.trenching.bot.console

import com.trenching.bot.console.ChartFixtures.TRADER_A
import com.trenching.bot.console.ChartFixtures.at
import com.trenching.bot.console.ChartFixtures.curveTrade
import com.trenching.bot.console.ChartFixtures.observation
import com.trenching.bot.console.ChartFixtures.tradeObservation
import com.trenching.bot.console.application.ChartPoint
import com.trenching.bot.console.application.ChartPosition
import com.trenching.bot.console.application.PointValuation
import com.trenching.bot.console.application.SlotExecutionOrder
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.TradeSide
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class SlotExecutionOrderTest {
    private val time = at("2026-10-21T04:10:18Z")
    private val sol = 60_000_000_000L
    private val token = 500_000_000_000_000L

    @Test
    fun `trades of one slot follow the chain of their curve reserves, not their ids`() {
        val first = curveTrade("c", time, 10, sol, token)
        val second = curveTrade("a", time, 10, sol + 1_000_000_000L, token - 30_000_000_000_000L, TradeSide.SELL, lamports = 400_000_000L,
            tokens = 10_000_000_000_000L)
        val third = curveTrade("b", time, 10, sol + 600_000_000L, token - 20_000_000_000_000L)

        val ordered = SlotExecutionOrder.order(points(first, second, third))

        assertEquals(listOf("c", "a", "b"), ordered.map { it.point.observation.id })
        assertEquals(listOf(0, 1, 2), ordered.map { it.indexInSlot })
        assertEquals(listOf(true, true, true), ordered.map { it.proven })
    }

    @Test
    fun `a slot whose reserves do not form one chain keeps its given order and is not proven`() {
        val one = curveTrade("a", time, 10, sol, token)
        val unrelated = curveTrade("b", time, 10, sol * 2, token / 2)

        val ordered = SlotExecutionOrder.order(points(one, unrelated))

        assertEquals(listOf("a", "b"), ordered.map { it.point.observation.id })
        assertEquals(listOf(false, false), ordered.map { it.proven })
    }

    @Test
    fun `a slot with an observation that holds no trade keeps its given order`() {
        val traded = curveTrade("a", time, 10, sol, token)
        val priceOnly = observation("b", time, "0.0000001", slot = 10)

        assertEquals(listOf(false, false), SlotExecutionOrder.order(points(traded, priceOnly)).map { it.proven })
    }

    @Test
    fun `slots stay in order and a slot with one trade is proven`() {
        val early = tradeObservation("z", time, TRADER_A, slot = 10)
        val late = tradeObservation("a", time.plusSeconds(1), TRADER_A, slot = 12)

        val ordered = SlotExecutionOrder.order(points(early, late))

        assertEquals(listOf("z", "a"), ordered.map { it.point.observation.id })
        assertEquals(listOf(0, 0), ordered.map { it.indexInSlot })
        assertEquals(listOf(true, true), ordered.map { it.proven })
    }

    private fun points(vararg observations: MarketObservation): List<ChartPoint> =
        observations.sortedWith(ChartPosition.ORDER).map { ChartPoint(it, PointValuation.RatesUnavailable) }
}
