package com.trenching.bot.console

import com.trenching.bot.console.ChartFixtures.TRACKED
import com.trenching.bot.console.ChartFixtures.TRADER_A
import com.trenching.bot.console.ChartFixtures.at
import com.trenching.bot.console.ChartFixtures.curveTrade
import com.trenching.bot.console.ChartFixtures.observation
import com.trenching.bot.console.application.ChartPoint
import com.trenching.bot.console.application.ChartPosition
import com.trenching.bot.console.application.ChartTicks
import com.trenching.bot.console.application.ChartValuation
import com.trenching.bot.console.application.SlotClock
import com.trenching.bot.console.application.SlotExecutionOrder
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.OrderedReferenceRates
import com.trenching.bot.marketdata.domain.TotalSupplyValuation
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.protocol.pumpfun.BondingCurve
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ChartTicksTest {
    private val time = at("2026-10-21T04:10:18Z")
    private val rate = BigDecimal("105.98")
    private val rates = OrderedReferenceRates(listOf(ReferenceRate(at("2026-10-21T04:10:00Z"), rate, "test-rates")))
    private val sol = 60_000_000_000L
    private val token = 500_000_000_000_000L
    private val buy = curveTrade("b", time, 10, sol, token, trader = TRACKED)
    private val sell = curveTrade("a", time, 10, sol + 1_000_000_000L, token - 30_000_000_000_000L, TradeSide.SELL, lamports = 400_000_000L,
        tokens = 10_000_000_000_000L, trader = TRADER_A)
    private val clock = SlotClock.of(listOf(buy, sell))

    @Test
    fun `every trade is a tick in execution order with its execution and curve price`() {
        val ticks = ticks(listOf(sell, buy), limit = 10)

        assertTrue(ticks.included)
        assertEquals(2, ticks.total)
        assertEquals(listOf("b", "a"), ticks.items.map { it.observationId })
        val first = ticks.items.first()
        assertEquals(0, first.indexInSlot)
        assertTrue(first.slotOrderProven)
        assertTrue(first.tracked)
        assertEquals(TradeSide.BUY, first.side)
        assertEquals(buy.price, first.price)
        val curvePrice = BondingCurve.price(BigInteger.valueOf(sol + 1_000_000_000L), BigInteger.valueOf(token - 30_000_000_000_000L))
        assertEquals(curvePrice, first.curvePrice)
        assertEquals(TotalSupplyValuation.usdt(curvePrice, rate), first.curveMcap)
        assertEquals(TotalSupplyValuation.usdt(buy.price, rate), first.mcap)
        assertEquals(time, first.estimatedTime)
        assertFalse(ticks.items.last().tracked)
    }

    @Test
    fun `more trades than the limit leave the ticks out but still count them`() {
        val ticks = ticks(listOf(sell, buy), limit = 1)

        assertFalse(ticks.included)
        assertEquals(2, ticks.total)
        assertTrue(ticks.items.isEmpty())
    }

    @Test
    fun `ticks are left out unless asked for`() {
        assertFalse(ticks(listOf(buy), limit = 0).included)
    }

    @Test
    fun `an observation without a trade is a tick without trader or amounts`() {
        val priceOnly = observation("p", time, "0.0000001", slot = 11)

        val tick = ticks(listOf(priceOnly), limit = 10).items.single()

        assertNull(tick.side)
        assertNull(tick.trader)
        assertNull(tick.solLamports)
        assertFalse(tick.tracked)
    }

    private fun ticks(observations: List<MarketObservation>, limit: Int) = ChartTicks.of(
        SlotExecutionOrder.order(observations.sortedWith(ChartPosition.ORDER)
            .map { ChartPoint(it, ChartValuation.of(it, rates)) }),
        TRACKED, clock, rates, limit)
}
