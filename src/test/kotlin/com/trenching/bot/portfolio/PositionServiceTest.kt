package com.trenching.bot.portfolio

import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.application.PositionService
import com.trenching.bot.portfolio.domain.CloseOutcome
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.portfolio.domain.PositionClose
import com.trenching.bot.portfolio.domain.PositionState
import com.trenching.bot.portfolio.domain.RecordedThrough
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PositionServiceTest {
    private val dataset = DipSample.load()
    private val config = SimulationConfig.mvp()
    private val scenario = ReplayRunner().run(dataset, config).scenarios.single { it.intent.pair == DipSample.pair(DipSample.TAKE_PROFIT) }
    private val closed = requireNotNull(scenario.position)

    /** Bought 1160584.892249 tokens at slot 1003, cost basis 0.10225 SOL; the horizon ends at slot 5500. */
    private val open: Position = closed.copy(state = PositionState.OPEN, close = null, exitTrigger = null)

    /** Post-trade curve of `fixture:TP:1006:0`: spot 0.000000115, protocol fee 95 bps and creator fee 30 bps. */
    private val template = dataset.observations.single { it.id == "fixture:TP:1006:0" }

    private fun observation(slot: Long, price: String, curve: CurveState = template.curve, curveComplete: Boolean = false, n: Int = 0) =
        template.copy(id = "test:$slot:$n", sourceEventId = "test:$slot:$n", slot = Slot.of(slot), priceSlot = Slot.of(slot),
            price = BigDecimal(price), curve = curve, curveComplete = curveComplete)

    private fun config(latencySlots: Long = 1, transactionCostSol: String = "0.001") =
        SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, latencySlots, BigDecimal(transactionCostSol))

    /** The fill trade, whose post-trade curve the position holds against until a later trade. */
    private val fillTrade = dataset.observations.single { it.id == closed.fill.observationId }

    private fun close(vararg observations: MarketObservation, config: SimulationConfig = this.config) =
        PositionService().close(open, scenario.intent, OrderedMarketData(listOf(fillTrade) + observations), config, WHOLE_HORIZON)

    private fun closed(vararg observations: MarketObservation, config: SimulationConfig = this.config): PositionClose =
        assertNotNull(assertIs<CloseOutcome.Closed>(close(*observations, config = config)).position.close)

    private fun reserves(curve: CurveState, realSol: Long): CurveState =
        curve.copy(reserves = CurveReserves(curve.reserves.virtualSol, curve.reserves.virtualToken, BigInteger.valueOf(realSol),
            curve.reserves.realToken))

    @Test
    fun `take profit and stop loss levels come from the curve execution price`() {
        val opened = PositionService().open(scenario.intent, closed.fill, OrderedMarketData(dataset.observations), config)
        assertEquals(0, BigDecimal("0.00000011201248686606967374718216755569").compareTo(opened.takeProfitPrice))
        assertEquals(0, BigDecimal("0.00000007323893372012247898854218647872").compareTo(opened.stopLossPrice))
        assertNull(opened.exitTrigger)
    }

    @Test
    fun `the exit sells into the exit trade's post-trade curve, paying its fees and the transaction cost`() {
        val close = closed(observation(1005, "0.000000113"), observation(1006, "0.000000115"))

        assertEquals(CloseReason.TAKE_PROFIT, close.reason)
        assertEquals(Slot.of(1006), close.slot)
        assertEquals(0, BigDecimal("0.00000011474828329182461687631176780630").compareTo(close.executionPrice))
        assertEquals("0.133175124000", close.grossProceeds.toPlainString())
        assertEquals("0.001664690000", close.fee.toPlainString())
        assertEquals(125, close.feeBps)
        assertEquals("0.001000000000", close.transactionCost.toPlainString())
        assertEquals(-22, close.priceImpactBps)
        assertEquals("0.130510434000", close.netProceeds.toPlainString())
        assertEquals("0.028260434000", close.realizedPnl.toPlainString())
    }

    @Test
    fun `the exit fees use the exit trade's basis points`() {
        val close = closed(observation(1005, "0.000000113"), observation(1006, "0.000000115", template.curve.copy(feeBps = 100, creatorFeeBps = 0)))
        assertEquals("0.001331752000", close.fee.toPlainString())
        assertEquals(100, close.feeBps)
    }

    @Test
    fun `a trade whose curve cannot pay the sale is skipped, and without one within 60 s the position is unresolved`() {
        val poor = reserves(template.curve, 100_000_000)
        assertEquals(Slot.of(1007), closed(observation(1005, "0.000000113"), observation(1006, "0.000000115", poor),
            observation(1007, "0.000000115")).slot)
        val past = observation(1007, "0.000000115").let { it.copy(observedTime = it.observedTime.plusSeconds(61)) }
        val unavailable = close(observation(1005, "0.000000113"), observation(1006, "0.000000115", poor), past)
        assertEquals(RejectionReason.UNRESOLVED, assertIs<CloseOutcome.Unavailable>(unavailable).reason)
    }

    @Test
    fun `a net at or below zero still closes with its loss`() {
        val cheap = dataset.observations.single { it.id == "fixture:SL:2005:0" }.curve
        val close = closed(observation(1005, "0.00000005"), observation(1006, "0.00000005", cheap), config = config(transactionCostSol = "0.1"))

        assertEquals(CloseReason.STOP_LOSS, close.reason)
        assertTrue(close.netProceeds.signum() < 0, close.netProceeds.toPlainString())
        assertEquals(close.netProceeds.subtract(open.fill.costBasis), close.realizedPnl)
    }

    @Test
    fun `exit latency fills at the first eligible trade that many slots after the trigger`() {
        val observations = arrayOf(observation(1004, "0.0000002"), observation(1005, "0.0000002"), observation(1006, "0.0000002"),
            observation(1007, "0.0000002"))
        assertEquals(Slot.of(1005), closed(*observations).slot)
        assertEquals(Slot.of(1007), closed(*observations, config = config(latencySlots = 3)).slot)
    }

    @Test
    fun `a stop loss trigger exits at the next slot's trade, never in the trigger's own slot`() {
        val outcome = assertIs<CloseOutcome.Closed>(close(observation(1004, "0.00000007"), observation(1004, "0.0000002", n = 1),
            observation(1005, "0.00000006")))
        val position = outcome.position
        assertEquals(CloseReason.STOP_LOSS, assertNotNull(position.exitTrigger).reason)
        assertEquals(Slot.of(1005), assertNotNull(position.close).slot)
        assertEquals(CloseReason.STOP_LOSS, assertNotNull(position.close).reason)
    }

    @Test
    fun `a known liquidity quote no longer limits the exit`() {
        val close = closed(observation(1004, "0.0000002"), observation(1005, "0.0000002").copy(liquidityQuote = BigDecimal("0.000001")))
        assertEquals(Slot.of(1005), close.slot)
    }

    @Test
    fun `the curve completing stops the position as migrated, keeping any trigger`() {
        val complete = template.curve.copy(reserves = CurveReserves(template.curve.reserves.virtualSol, template.curve.reserves.virtualToken,
            template.curve.reserves.realSol, BigInteger.ZERO))
        val before = assertIs<CloseOutcome.Unavailable>(close(observation(1004, "0.00000006"),
            observation(1005, "0.00000005", complete, curveComplete = true)))
        assertEquals(RejectionReason.MIGRATED, before.reason)
        assertEquals(Slot.of(1004), assertNotNull(before.position.exitTrigger).slot)
        val untriggered = assertIs<CloseOutcome.Unavailable>(close(observation(1004, "0.00000009", complete, curveComplete = true)))
        assertEquals(RejectionReason.MIGRATED, untriggered.reason)
    }

    @Test
    fun `without a trigger the horizon ends the position, as it does a trigger whose sale slot lies past it`() {
        assertEquals(RejectionReason.HORIZON_REACHED,
            assertIs<CloseOutcome.Unavailable>(close(observation(1004, "0.00000009"), observation(5501, "0.0000003"))).reason)
        val lastSlot = assertIs<CloseOutcome.Unavailable>(close(observation(5500, "0.0000003")))
        assertEquals(RejectionReason.HORIZON_REACHED, lastSlot.reason)
        assertEquals(CloseReason.TAKE_PROFIT, assertNotNull(lastSlot.position.exitTrigger).reason)
    }

    @Test
    fun `inconsistent recovered position state is rejected and a closed position cannot close again`() {
        assertFailsWith<IllegalArgumentException> {
            PositionService().close(closed.copy(close = null), scenario.intent, OrderedMarketData(emptyList()), config, WHOLE_HORIZON)
        }
        val again = PositionService().close(closed, scenario.intent, OrderedMarketData(emptyList()), config, WHOLE_HORIZON)
        assertEquals(closed, assertIs<CloseOutcome.AlreadyClosed>(again).position)
    }

    private companion object {
        val WHOLE_HORIZON = RecordedThrough(Slot.of(5500), null)
    }
}
