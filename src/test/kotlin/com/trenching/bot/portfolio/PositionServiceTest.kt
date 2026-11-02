package com.trenching.bot.portfolio

import com.trenching.bot.common.Base58
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.application.PositionService
import com.trenching.bot.portfolio.domain.CloseOutcome
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.ExitPriceAssumption
import com.trenching.bot.portfolio.domain.ExitTrigger
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.portfolio.domain.PositionClose
import com.trenching.bot.portfolio.domain.PositionState
import com.trenching.bot.portfolio.domain.RecordedThrough
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.strategy.domain.PriceMeasure
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
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

    private fun observation(
        slot: Long,
        price: String,
        curve: CurveState = template.curve,
        curveComplete: Boolean = false,
        n: Int = 0,
        trade: ObservedTrade? = null,
        secondsLater: Long = 0,
    ) = template.copy(id = "test:$slot:$n", sourceEventId = "test:$slot:$n", slot = Slot.of(slot), priceSlot = Slot.of(slot),
        price = BigDecimal(price), curve = curve, curveComplete = curveComplete, trade = trade,
        observedTime = template.observedTime.plusSeconds(secondsLater))

    private fun config(
        latencySlots: Long = 1,
        transactionCostSol: String = "0.001",
        priceMeasure: PriceMeasure = PriceMeasure.TRADE_AVERAGE,
        followWalletSells: Boolean = false,
        maxHoldMs: Long? = null,
    ) = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, latencySlots, BigDecimal(transactionCostSol),
        maxHoldMs = maxHoldMs, hardExitStartMs = maxHoldMs?.let { HARD_EXIT_START_MS }, priceMeasure = priceMeasure,
        followWalletSells = followWalletSells)

    private val postTrade = config(priceMeasure = PriceMeasure.POST_TRADE)
    private val followingSells = config(followWalletSells = true)

    /** The tracked wallet, whose buy started the cycle, and another trader. */
    private val wallet = scenario.intent.wallet.value
    private val otherTrader = Base58.encode(ByteArray(32) { 7 })

    private fun trade(trader: String = wallet, side: TradeSide = TradeSide.SELL, tokenRaw: Long = 1_000_000_000_000) =
        ObservedTrade.of(trader, side, BigInteger.valueOf(100_000_000), BigInteger.valueOf(tokenRaw))

    /** [template]'s curve moved to the post-trade [spot] by its virtual token reserve, keeping its SOL and its real-to-virtual gap. */
    private fun curveAt(spot: String): CurveState {
        val reserves = template.curve.reserves
        val virtualToken = BigDecimal(reserves.virtualSol).divide(BigDecimal(spot).movePointRight(3), 0, RoundingMode.DOWN).toBigIntegerExact()
        return template.curve.copy(reserves = CurveReserves(reserves.virtualSol, virtualToken, reserves.realSol,
            virtualToken.subtract(reserves.virtualToken.subtract(reserves.realToken))))
    }

    /** A post-trade curve between the position's levels, 0.00000009 SOL per token. */
    private val neutral = curveAt("0.00000009")

    /** The fill trade, whose post-trade curve the position holds against until a later trade. */
    private val fillTrade = dataset.observations.single { it.id == closed.fill.observationId }

    private fun close(vararg observations: MarketObservation, config: SimulationConfig = this.config, position: Position = open) =
        PositionService().close(position, scenario.intent, OrderedMarketData(listOf(fillTrade) + observations), config, WHOLE_HORIZON)

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
    fun `under the post-trade measure a take profit fires on the curve after the trade, never on its average`() {
        val postTradeAbove = observation(1004, "0.00000009", curveAt("0.00000012"))
        val averageAbove = observation(1004, "0.00000012", neutral)

        assertEquals(CloseReason.TAKE_PROFIT, closed(postTradeAbove, observation(1005, "0.00000009", neutral), config = postTrade).reason)
        assertEquals(RejectionReason.HORIZON_REACHED, assertIs<CloseOutcome.Unavailable>(close(averageAbove, config = postTrade)).reason)
        assertEquals(RejectionReason.HORIZON_REACHED, assertIs<CloseOutcome.Unavailable>(close(postTradeAbove)).reason)
        assertEquals(CloseReason.TAKE_PROFIT, closed(averageAbove, observation(1005, "0.00000009")).reason)
    }

    @Test
    fun `under the post-trade measure a stop loss fires on the curve after the trade, never on its average`() {
        val postTradeBelow = observation(1004, "0.00000009", curveAt("0.00000006"))
        val averageBelow = observation(1004, "0.00000006", neutral)

        assertEquals(CloseReason.STOP_LOSS, closed(postTradeBelow, observation(1005, "0.00000009", neutral), config = postTrade).reason)
        assertEquals(RejectionReason.HORIZON_REACHED, assertIs<CloseOutcome.Unavailable>(close(averageBelow, config = postTrade)).reason)
        assertEquals(RejectionReason.HORIZON_REACHED, assertIs<CloseOutcome.Unavailable>(close(postTradeBelow)).reason)
        assertEquals(CloseReason.STOP_LOSS, closed(averageBelow, observation(1005, "0.00000009")).reason)
    }

    @Test
    fun `a wallet sell while holding sells the whole position into the next trade as wallet sold`() {
        val sell = observation(1004, "0.00000009", trade = trade())
        val outcome = assertIs<CloseOutcome.Closed>(close(sell, observation(1005, "0.00000009"), config = followingSells))
        val close = assertNotNull(outcome.position.close)

        assertEquals(ExitTrigger(sell.id, Slot.of(1004), sell.observedTime, CloseReason.WALLET_SOLD), outcome.position.exitTrigger)
        assertEquals(CloseReason.WALLET_SOLD, close.reason)
        assertEquals(Slot.of(1005), close.slot)
        assertEquals(ExitPriceAssumption.NEXT_TRADE, close.priceAssumption)
        assertEquals("0.133175124000", close.grossProceeds.toPlainString())
    }

    @Test
    fun `a wallet sell with no trade within 60 s sells against the standing curve, or stays unresolved when it cannot pay`() {
        val sell = observation(1004, "0.00000009", trade = trade())
        val late = observation(1005, "0.00000009", secondsLater = 61)

        val standing = closed(sell, late, config = followingSells)
        assertEquals(CloseReason.WALLET_SOLD, standing.reason)
        assertEquals(ExitPriceAssumption.STANDING_CURVE, standing.priceAssumption)
        assertEquals(Slot.of(1005), standing.slot)
        assertEquals(sell.id, standing.observationId)

        val poorSell = observation(1004, "0.00000009", reserves(template.curve, 100_000_000), trade = trade())
        val unresolved = assertIs<CloseOutcome.Unavailable>(close(poorSell, late, config = followingSells))
        assertEquals(RejectionReason.UNRESOLVED, unresolved.reason)
        assertEquals(CloseReason.WALLET_SOLD, assertNotNull(unresolved.position.exitTrigger).reason)
    }

    @Test
    fun `a wallet sell of one percent of its tokens still sells the whole position`() {
        val next = observation(1005, "0.00000009")

        val all = closed(observation(1004, "0.00000009", trade = trade(tokenRaw = 1_000_000_000_000)), next, config = followingSells)
        val onePercent = closed(observation(1004, "0.00000009", trade = trade(tokenRaw = 10_000_000_000)), next, config = followingSells)

        assertEquals(all, onePercent)
        assertEquals(CloseReason.WALLET_SOLD, onePercent.reason)
    }

    @Test
    fun `an ineligible wallet sell still triggers and the standing curve is the last eligible trade's`() {
        val invalidSell = observation(1004, "0.00000009", reserves(template.curve, 100_000_000), trade = trade())
            .copy(quality = ObservationQuality.INVALID)

        val close = closed(invalidSell, observation(1005, "0.00000009", secondsLater = 61), config = followingSells)

        assertEquals(CloseReason.WALLET_SOLD, close.reason)
        assertEquals(fillTrade.id, close.observationId)
        assertEquals(Slot.of(1005), close.slot)
    }

    @Test
    fun `a wallet sell after our dip trigger and through our fill slot exits as soon as we hold`() {
        val afterFill = observation(1003, "0.00000009", n = 1, trade = trade())
        val beforeFill = afterFill.copy(id = "a:1003:0", sourceEventId = "a:1003:0")

        listOf(afterFill, beforeFill).forEach { sell ->
            val outcome = assertIs<CloseOutcome.Closed>(close(sell, observation(1004, "0.00000009"), config = followingSells))
            assertEquals(ExitTrigger(sell.id, open.fill.actualSlot, fillTrade.observedTime, CloseReason.WALLET_SOLD), outcome.position.exitTrigger)
            assertEquals(Slot.of(1004), assertNotNull(outcome.position.close).slot)
            assertEquals(CloseReason.WALLET_SOLD, assertNotNull(outcome.position.close).reason)
        }
        assertEquals(RejectionReason.HORIZON_REACHED,
            assertIs<CloseOutcome.Unavailable>(close(afterFill, observation(1004, "0.00000009"))).reason)
    }

    @Test
    fun `a take profit or stop loss before the wallet sell keeps its own reason`() {
        val sell = observation(1005, "0.00000009", trade = trade())

        assertEquals(CloseReason.TAKE_PROFIT, closed(observation(1004, "0.0000002"), sell, config = followingSells).reason)
        assertEquals(CloseReason.STOP_LOSS, closed(observation(1004, "0.00000006"), sell, config = followingSells).reason)
    }

    @Test
    fun `a wallet sell that is also the stop loss crossing exits as wallet sold`() {
        val close = closed(observation(1004, "0.00000006", trade = trade()), observation(1005, "0.00000009"), config = followingSells)

        assertEquals(CloseReason.WALLET_SOLD, close.reason)
    }

    @Test
    fun `within a slot the wallet's sell is read before another trader's stop loss crossing, whichever id sorts first`() {
        val next = observation(1005, "0.00000009")
        val outcomes = listOf(0 to 1, 1 to 0).map { (crossingN, sellN) ->
            val crossing = observation(1004, "0.00000006", n = crossingN, trade = trade(otherTrader, TradeSide.BUY))
            val sell = observation(1004, "0.00000009", n = sellN, trade = trade())
            sell to assertIs<CloseOutcome.Closed>(close(crossing, sell, next, config = followingSells)).position
        }

        outcomes.forEach { (sell, position) ->
            assertEquals(ExitTrigger(sell.id, Slot.of(1004), sell.observedTime, CloseReason.WALLET_SOLD), position.exitTrigger)
            assertEquals(CloseReason.WALLET_SOLD, assertNotNull(position.close).reason)
        }
        assertEquals(outcomes[0].second.close, outcomes[1].second.close)
    }

    @Test
    fun `a take profit or stop loss crossing in an earlier slot than the wallet's sell keeps its own reason`() {
        val sell = observation(1005, "0.00000009", n = 0, trade = trade())

        assertEquals(CloseReason.STOP_LOSS, closed(observation(1004, "0.00000006", n = 1), sell, observation(1006, "0.00000009"),
            config = followingSells).reason)
    }

    @Test
    fun `a wallet sell at or after the exit start changes nothing`() {
        val timed = config(followWalletSells = true, maxHoldMs = 10_000)
        val position = PositionService().open(scenario.intent, closed.fill, OrderedMarketData(listOf(fillTrade)), timed)
        assertEquals(fillTrade.observedTime.plusSeconds(10), position.exitStart)

        val atStart = close(observation(1004, "0.00000009", trade = trade(), secondsLater = 10), config = timed, position = position)
        val before = close(observation(1004, "0.00000009", trade = trade(), secondsLater = 9), observation(1005, "0.00000009", secondsLater = 9),
            config = timed, position = position)

        assertEquals(CloseReason.TIME_EXIT, assertNotNull(assertIs<CloseOutcome.Closed>(atStart).position.close).reason)
        assertEquals(CloseReason.WALLET_SOLD, assertNotNull(assertIs<CloseOutcome.Closed>(before).position.close).reason)
    }

    @Test
    fun `another trader's sell or the wallet's own buy never triggers an exit`() {
        val outcome = close(observation(1004, "0.00000009", trade = trade(otherTrader)),
            observation(1004, "0.00000009", n = 1, trade = trade(side = TradeSide.BUY)), observation(1005, "0.00000009"), config = followingSells)

        assertEquals(RejectionReason.HORIZON_REACHED, assertIs<CloseOutcome.Unavailable>(outcome).reason)
        assertNull(assertIs<CloseOutcome.Unavailable>(outcome).position.exitTrigger)
    }

    @Test
    fun `without the wallet-sell rule the wallet's sells are ignored while holding`() {
        val outcome = assertIs<CloseOutcome.Unavailable>(close(observation(1004, "0.00000009", trade = trade()), observation(1005, "0.00000009")))

        assertEquals(RejectionReason.HORIZON_REACHED, outcome.reason)
        assertNull(outcome.position.exitTrigger)
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
        const val HARD_EXIT_START_MS = 240_000L
    }
}
