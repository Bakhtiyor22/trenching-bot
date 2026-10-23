package com.trenching.bot.portfolio

import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.application.PositionService
import com.trenching.bot.portfolio.domain.CloseOutcome
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.ExitPriceAssumption
import com.trenching.bot.portfolio.domain.ExitTrigger
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.portfolio.domain.PositionClose
import com.trenching.bot.portfolio.domain.RecordedThrough
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.strategy.domain.TradeIntent
import com.trenching.bot.support.DipSample
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * Spec P11's time exit and the standing-curve sales, on the sample's take-profit position: t0 and the fill trade
 * `fixture:TP:1003:0` are both at [T0], 1160584892249 raw tokens cost 0.10225 SOL, take profit is at 0.000000112012 and stop
 * loss at 0.000000073239, and the horizon ends at slot 5500.
 */
class TimedExitTest {
    private val dataset = DipSample.load()
    private val scenario = ReplayRunner().run(dataset, SimulationConfig.mvp()).scenarios.single {
        it.intent.pair == DipSample.pair(DipSample.TAKE_PROFIT)
    }
    private val intent = scenario.intent
    private val fill = assertIs<FillOutcome.Filled>(scenario.outcome).fill
    private val fillTrade = dataset.observations.single { it.id == FILL_TRADE }

    /** Post-trade curve of `fixture:TP:1006:0`: selling the position into it is the sample's hand-checked take profit. */
    private val template = dataset.observations.single { it.id == "fixture:TP:1006:0" }
    private val poor = template.curve.copy(reserves = CurveReserves(template.curve.reserves.virtualSol,
        template.curve.reserves.virtualToken, BigInteger.valueOf(100_000_000), template.curve.reserves.realToken))

    @Test
    fun `the exit starts at the earlier of the maximum hold from the fill and the hard exit from the buy`() {
        assertEquals(T0.plusSeconds(180), open(TIMED).exitStart)
        assertEquals(T0.plusSeconds(60), open(timed(maxHoldMs = 180_000, hardExitStartMs = 60_000)).exitStart)
        assertEquals(T0.plusSeconds(40), open(TIMED, intent.copy(t0 = T0.minusSeconds(200))).exitStart)
        assertNull(open(SimulationConfig.mvp()).exitStart)
        assertNull(open(TIMED, intent.copy(t0 = null)).exitStart)
    }

    @Test
    fun `a take profit before the exit start keeps its reason when its sale lands after it`() {
        val position = closedPosition(TIMED, observation(1005, 179, TAKE_PROFIT), observation(1006, 200, "0.00000005"))

        assertEquals(ExitTrigger("test:1005", Slot.of(1005), T0.plusSeconds(179), CloseReason.TAKE_PROFIT), position.exitTrigger)
        val close = assertNotNull(position.close)
        assertEquals(CloseReason.TAKE_PROFIT, close.reason)
        assertEquals(Slot.of(1006), close.slot)
        assertEquals("test:1006", close.observationId)
        assertEquals(ExitPriceAssumption.NEXT_TRADE, close.priceAssumption)
    }

    @Test
    fun `a trade in the exit start's own second is already past it, so its take profit crossing is a time exit`() {
        val position = closedPosition(TIMED, observation(1005, 180, TAKE_PROFIT))

        assertEquals(ExitTrigger(FILL_TRADE, Slot.of(1005), T0.plusSeconds(180), CloseReason.TIME_EXIT), position.exitTrigger)
        val close = assertNotNull(position.close)
        assertEquals(CloseReason.TIME_EXIT, close.reason)
        assertEquals(FILL_TRADE, close.observationId)
        assertEquals(Slot.of(1005), close.slot)
        assertEquals(ExitPriceAssumption.STANDING_CURVE, close.priceAssumption)
    }

    @Test
    fun `a time exit on an active token sells against the last eligible curve before the exit start`() {
        val close = closed(TIMED, observation(1004, 60, "0.0000001", template.curve),
            observation(1005, 179, "0.0000001", poor).copy(quality = ObservationQuality.INVALID),
            observation(1006, 181, "0.0000002", fillTrade.curve))

        assertEquals(CloseReason.TIME_EXIT, close.reason)
        assertEquals("test:1004", close.observationId)
        assertEquals(Slot.of(1006), close.slot)
        assertEquals("0.133175124000", close.grossProceeds.toPlainString())
        assertEquals(DipSample.TAKE_PROFIT_PNL, close.realizedPnl.toPlainString())
    }

    @Test
    fun `a time exit on a token that stopped trading closes once the coverage end time reaches the exit start`() {
        val position = closedPosition(TIMED, recorded = RecordedThrough(Slot.of(5500), T0.plusSeconds(180)))

        assertEquals(ExitTrigger(FILL_TRADE, Slot.of(5500), T0.plusSeconds(180), CloseReason.TIME_EXIT), position.exitTrigger)
        assertEquals(Slot.of(5500), assertNotNull(position.close).slot)
        assertEquals(FILL_TRADE, assertNotNull(position.close).observationId)
    }

    @Test
    fun `without a coverage end time reaching the exit start, a token that stopped trading stays open at the horizon`() {
        listOf(
            close(TIMED),
            close(TIMED, recorded = RecordedThrough(Slot.of(5500), T0.plusSeconds(179))),
            close(TIMED, observation(5501, 300, "0.0000001")),
        ).forEach { outcome ->
            val unavailable = assertIs<CloseOutcome.Unavailable>(outcome)
            assertEquals(RejectionReason.HORIZON_REACHED, unavailable.reason)
            assertNull(unavailable.position.exitTrigger)
            assertEquals(T0.plusSeconds(180), unavailable.position.exitStart)
        }
    }

    @Test
    fun `a take profit sells into the next trade when it lands within 60 s of the trigger, the 60th second included`() {
        listOf(59L, 60L).forEach { seconds ->
            val close = closed(SimulationConfig.mvp(), observation(1005, 0, TAKE_PROFIT, fillTrade.curve), observation(1006, seconds, "0.0000001"))

            assertEquals(CloseReason.TAKE_PROFIT, close.reason)
            assertEquals(ExitPriceAssumption.NEXT_TRADE, close.priceAssumption)
            assertEquals("test:1006", close.observationId)
            assertEquals(DipSample.TAKE_PROFIT_PNL, close.realizedPnl.toPlainString())
        }
    }

    @Test
    fun `a take profit whose next trade lands 61 s later sells against the curve standing at its sale slot`() {
        listOf(observation(1006, 61, "0.0000001", poor), observation(2205, 480, "0.0000001", poor, curveComplete = true)).forEach { later ->
            val close = closed(SimulationConfig.mvp(), observation(1005, 0, TAKE_PROFIT, template.curve), later)

            assertEquals(CloseReason.TAKE_PROFIT, close.reason)
            assertEquals(ExitPriceAssumption.STANDING_CURVE, close.priceAssumption)
            assertEquals(Slot.of(1006), close.slot)
            assertEquals("test:1005", close.observationId)
            assertEquals(DipSample.TAKE_PROFIT_PNL, close.realizedPnl.toPlainString())
        }
    }

    @Test
    fun `a take profit on a token that stopped trading sells against the standing curve once the coverage end time passes 60 s`() {
        val trigger = observation(1005, 0, TAKE_PROFIT, template.curve)
        val close = closed(SimulationConfig.mvp(), trigger, recorded = RecordedThrough(Slot.of(1006), T0.plusSeconds(61)))

        assertEquals(ExitPriceAssumption.STANDING_CURVE, close.priceAssumption)
        assertEquals(Slot.of(1006), close.slot)
        assertEquals("test:1005", close.observationId)
        assertEquals(DipSample.TAKE_PROFIT_PNL, close.realizedPnl.toPlainString())

        val slow = timed(latencySlots = 3)
        val observations = arrayOf(observation(1005, 0, TAKE_PROFIT, fillTrade.curve), observation(1006, 0, "0.0000001", template.curve),
            observation(1007, 0, "0.0000001", poor).copy(quality = ObservationQuality.INVALID))
        val standing = closed(slow, *observations, recorded = RecordedThrough(Slot.of(1008), T0.plusSeconds(61)))
        assertEquals(Slot.of(1008), standing.slot)
        assertEquals("test:1006", standing.observationId)
    }

    @Test
    fun `a take profit stays open at the horizon until the recording proves no trade can land within 60 s`() {
        val trigger = observation(1005, 0, TAKE_PROFIT, template.curve)
        listOf(
            close(SimulationConfig.mvp(), trigger),
            close(SimulationConfig.mvp(), trigger, recorded = RecordedThrough(Slot.of(5500), T0.plusSeconds(60))),
            close(SimulationConfig.mvp(), trigger, recorded = RecordedThrough(Slot.of(1005), T0.plusSeconds(61))),
            close(SimulationConfig.mvp(), trigger, observation(1005, 61, "0.0000001").copy(id = "test:1005:1")),
        ).forEach { outcome ->
            val unproven = assertIs<CloseOutcome.Unavailable>(outcome)
            assertEquals(RejectionReason.HORIZON_REACHED, unproven.reason)
            assertEquals(ExitTrigger("test:1005", Slot.of(1005), T0, CloseReason.TAKE_PROFIT), unproven.position.exitTrigger)
        }
    }

    @Test
    fun `a curve that cannot pay the sale leaves the position open as unresolved, keeping its trigger`() {
        val timeExit = assertIs<CloseOutcome.Unavailable>(close(TIMED, observation(1004, 60, "0.0000001", poor), observation(1005, 181, "0.0000001")))
        assertEquals(RejectionReason.UNRESOLVED, timeExit.reason)
        assertEquals(ExitTrigger("test:1004", Slot.of(1005), T0.plusSeconds(180), CloseReason.TIME_EXIT), timeExit.position.exitTrigger)

        val standing = assertIs<CloseOutcome.Unavailable>(close(SimulationConfig.mvp(), observation(1005, 0, TAKE_PROFIT, poor),
            recorded = RecordedThrough(Slot.of(5500), T0.plusSeconds(61))))
        assertEquals(RejectionReason.UNRESOLVED, standing.reason)
        assertEquals(CloseReason.TAKE_PROFIT, assertNotNull(standing.position.exitTrigger).reason)
    }

    @Test
    fun `a take profit whose trades within 60 s cannot pay is unresolved once a later trade passes the deadline`() {
        val tried = arrayOf(observation(1005, 0, TAKE_PROFIT, template.curve), observation(1006, 30, "0.0000001", poor))

        val unresolved = assertIs<CloseOutcome.Unavailable>(close(SimulationConfig.mvp(), *tried, observation(1007, 61, "0.0000001")))
        assertEquals(RejectionReason.UNRESOLVED, unresolved.reason)
        assertEquals(CloseReason.TAKE_PROFIT, assertNotNull(unresolved.position.exitTrigger).reason)

        val unproven = assertIs<CloseOutcome.Unavailable>(close(SimulationConfig.mvp(), *tried))
        assertEquals(RejectionReason.HORIZON_REACHED, unproven.reason)
    }

    @Test
    fun `the curve completing while holding is a migration, unless the exit had already started`() {
        val migrated = assertIs<CloseOutcome.Unavailable>(close(TIMED, observation(1004, 179, "0.0000001", poor, curveComplete = true)))
        assertEquals(RejectionReason.MIGRATED, migrated.reason)
        assertNull(migrated.position.exitTrigger)

        val afterExitStart = closed(TIMED, observation(1004, 180, "0.0000001", poor, curveComplete = true))
        assertEquals(CloseReason.TIME_EXIT, afterExitStart.reason)
        assertEquals(FILL_TRADE, afterExitStart.observationId)
    }

    @Test
    fun `the curve completing within 60 s of a take profit is a migration`() {
        val migrated = assertIs<CloseOutcome.Unavailable>(close(SimulationConfig.mvp(), observation(1005, 0, TAKE_PROFIT),
            observation(1006, 60, "0.0000001", poor, curveComplete = true)))

        assertEquals(RejectionReason.MIGRATED, migrated.reason)
        assertEquals(CloseReason.TAKE_PROFIT, assertNotNull(migrated.position.exitTrigger).reason)
    }

    @Test
    fun `a time exit sells against the last trade of the fill's own slot after the fill trade`() {
        // A dump later in the fill slot 1003 leaves virtual SOL 40000000000 and virtual tokens 804750000000000. Gross:
        // 1160584892249 * 40000000000 / (804750000000000 + 1160584892249), rounded down, = 57603655 lamports. Fees round up:
        // ceil(57603655 * 95 / 10000) = 547235 plus ceil(57603655 * 30 / 10000) = 172811, so 720046. Net: 0.057603655 - 0.000720046
        // - 0.001 = 0.055883609 SOL. P&L: net less the 0.10225 cost basis = -0.046366391 SOL.
        val dump = observation(1003, 0, "0.00000005", DUMPED)
        val close = closed(TIMED, dump, recorded = RecordedThrough(Slot.of(5500), T0.plusSeconds(180)))

        assertEquals(CloseReason.TIME_EXIT, close.reason)
        assertEquals("test:1003", close.observationId)
        assertEquals("0.057603655000", close.grossProceeds.toPlainString())
        assertEquals("0.000720046000", close.fee.toPlainString())
        assertEquals("0.055883609000", close.netProceeds.toPlainString())
        assertEquals("-0.046366391000", close.realizedPnl.toPlainString())
    }

    @Test
    fun `the curve completing later in the fill's own slot is a migration`() {
        val migrated = assertIs<CloseOutcome.Unavailable>(close(TIMED, observation(1003, 0, "0.0000001", poor, curveComplete = true),
            recorded = RecordedThrough(Slot.of(5500), T0.plusSeconds(180))))

        assertEquals(RejectionReason.MIGRATED, migrated.reason)
        assertNull(migrated.position.exitTrigger)
    }

    @Test
    fun `when the hard exit started before the fill, the position sells against the fill trade's own curve`() {
        val position = closedPosition(TIMED, intent = intent.copy(t0 = T0.minusSeconds(241)))

        assertEquals(T0.minusSeconds(1), position.exitStart)
        assertEquals(ExitTrigger(FILL_TRADE, Slot.of(1003), T0.minusSeconds(1), CloseReason.TIME_EXIT), position.exitTrigger)
        assertEquals(Slot.of(1003), assertNotNull(position.close).slot)
    }

    @Test
    fun `without the buy's block time the exit cannot be timed, but take profit still ends the position`() {
        val position = closedPosition(TIMED, observation(1005, 600, TAKE_PROFIT), observation(1006, 601, "0.0000001"),
            intent = intent.copy(t0 = null))

        assertNull(position.exitStart)
        assertEquals(CloseReason.TAKE_PROFIT, assertNotNull(position.close).reason)
    }

    @Test
    fun `a time exit's pnl is reproducible by hand from the standing curve`() {
        // The standing curve is the fill trade's own (fixture:TP:1003:0): virtual SOL 52615016868, virtual tokens 611802521716526.
        // Tokens in: 1160584892249. Gross: 1160584892249 * 52615016868 / (611802521716526 + 1160584892249), rounded down,
        // = 99621319 lamports. Fees round up: ceil(99621319 * 95 / 10000) = 946403 plus ceil(99621319 * 30 / 10000) = 298864,
        // so 1245267 lamports. Net: 0.099621319 - 0.001245267 - 0.001 transaction cost = 0.097376052 SOL. P&L: net less the
        // 0.10225 cost basis = -0.004873948 SOL.
        val close = closed(TIMED, recorded = RecordedThrough(Slot.of(5500), T0.plusSeconds(181)))

        assertEquals(CloseReason.TIME_EXIT, close.reason)
        assertEquals(0, BigDecimal("0.00000008583716681590797880456892357829").compareTo(close.executionPrice))
        assertEquals("0.099621319000", close.grossProceeds.toPlainString())
        assertEquals("0.001245267000", close.fee.toPlainString())
        assertEquals(125, close.feeBps)
        assertEquals("0.001000000000", close.transactionCost.toPlainString())
        assertEquals(-19, close.priceImpactBps)
        assertEquals("0.097376052000", close.netProceeds.toPlainString())
        assertEquals("-0.004873948000", close.realizedPnl.toPlainString())
    }

    private fun open(config: SimulationConfig, intent: TradeIntent = this.intent): Position =
        PositionService().open(intent, fill, OrderedMarketData(listOf(fillTrade)), config)

    private fun close(
        config: SimulationConfig,
        vararg observations: MarketObservation,
        recorded: RecordedThrough = RecordedThrough(Slot.of(5500), null),
        intent: TradeIntent = this.intent,
    ): CloseOutcome = PositionService().close(open(config, intent), intent, OrderedMarketData(listOf(fillTrade) + observations), config, recorded)

    private fun closedPosition(
        config: SimulationConfig,
        vararg observations: MarketObservation,
        recorded: RecordedThrough = RecordedThrough(Slot.of(5500), null),
        intent: TradeIntent = this.intent,
    ): Position = assertIs<CloseOutcome.Closed>(close(config, *observations, recorded = recorded, intent = intent)).position

    private fun closed(
        config: SimulationConfig,
        vararg observations: MarketObservation,
        recorded: RecordedThrough = RecordedThrough(Slot.of(5500), null),
    ): PositionClose = assertNotNull(closedPosition(config, *observations, recorded = recorded).close)

    private fun observation(slot: Long, secondsAfterT0: Long, price: String, curve: CurveState = template.curve, curveComplete: Boolean = false) =
        template.copy(id = "test:$slot", sourceEventId = "test:$slot", slot = Slot.of(slot), priceSlot = Slot.of(slot),
            observedTime = T0.plusSeconds(secondsAfterT0), price = BigDecimal(price), curve = curve, curveComplete = curveComplete)

    private companion object {
        val T0: Instant = Instant.parse("2026-10-01T00:00:00Z")
        const val FILL_TRADE = "fixture:TP:1003:0"
        const val TAKE_PROFIT = "0.000000115"

        /** The mvp levels with spec P11's clocks: three minutes held or four from the buy, so the exit starts at T0 + 180 s. */
        fun timed(maxHoldMs: Long = 180_000, hardExitStartMs: Long = 240_000, latencySlots: Long = 1): SimulationConfig =
            SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, latencySlots, BigDecimal("0.001"),
                maxHoldMs = maxHoldMs, hardExitStartMs = hardExitStartMs)

        val TIMED = timed()

        /** The fill trade's curve after a large sell later in its slot; its real SOL still pays the position. */
        val DUMPED = CurveState(CurveReserves(BigInteger.valueOf(40_000_000_000), BigInteger.valueOf(804_750_000_000_000),
            BigInteger.valueOf(10_000_000_000), BigInteger.valueOf(524_750_000_000_000)), 95, 30)
    }
}
