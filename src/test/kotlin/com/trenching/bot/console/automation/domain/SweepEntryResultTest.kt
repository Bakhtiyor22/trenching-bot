package com.trenching.bot.console.automation.domain

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.papertrading.domain.PaperFill
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.ExitPriceAssumption
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.portfolio.domain.PositionClose
import com.trenching.bot.portfolio.domain.PositionState
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.domain.RunSummary
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Plan Rules 21–23: what one sweep entry's run, or its refusal, comes to on an item. */
class SweepEntryResultTest {
    @Test
    fun `a closed position counts its realized P&L at scale 12 as both its realized and its worst case`() {
        val result = SweepEntryResult.ran(RUN, SweepCycle.Entered(position(close(CloseReason.TAKE_PROFIT, "0.0282604340")), null))

        assertEquals("TAKE_PROFIT", result.outcome)
        assertEquals(PositionState.CLOSED, result.positionState)
        assertEquals(BigDecimal("0.028260434000"), result.realizedPnl)
        assertEquals(BigDecimal("0.028260434000"), result.worstCasePnl)
        assertTrue(result.signal)
        assertEquals(Triple(RUN.id, RUN.configurationHash, RUN.engineVersion), Triple(result.runId, result.configurationHash, result.engineVersion))
    }

    @Test
    fun `a position still open counts minus its entry's notional, fees and transaction cost in the worst case`() {
        val result = SweepEntryResult.ran(RUN, SweepCycle.Entered(position(null), RejectionReason.HORIZON_REACHED))

        assertEquals("HORIZON_REACHED", result.outcome)
        assertEquals(PositionState.OPEN, result.positionState)
        assertEquals(0, result.realizedPnl.compareTo(BigDecimal.ZERO))
        assertEquals(12, result.realizedPnl.scale())
        assertEquals(BigDecimal("-0.102250000000"), result.worstCasePnl)
        assertTrue(result.signal)
    }

    @Test
    fun `a later skip is a signal with no P&L`() {
        val result = SweepEntryResult.ran(RUN, SweepCycle.Skipped(RejectionReason.NO_DIP))

        assertEquals("NO_DIP", result.outcome)
        assertNull(result.positionState)
        assertTrue(result.signal)
        assertEquals(BigDecimal("0E-12"), result.worstCasePnl)
        assertFalse(SweepOutcomes.unscored(result.outcome))
    }

    @Test
    fun `a buy at or below the minimum size is a valid non-signal, not unscored`() {
        val result = SweepEntryResult.ran(RUN, SweepCycle.Skipped(RejectionReason.SIGNAL_BELOW_MIN_SIZE))

        assertFalse(result.signal)
        assertFalse(SweepOutcomes.unscored(result.outcome))
    }

    @Test
    fun `an unknown size or reference is no signal and leaves the entry unscored`() {
        listOf(RejectionReason.SIGNAL_SIZE_UNAVAILABLE, RejectionReason.REFERENCE_UNAVAILABLE).forEach { reason ->
            val result = SweepEntryResult.ran(RUN, SweepCycle.Skipped(reason))

            assertFalse(result.signal, "$reason")
            assertTrue(SweepOutcomes.unscored(result.outcome), "$reason")
        }
    }

    @Test
    fun `a run without the item's cycle is no signal and unscored`() {
        val result = SweepEntryResult.ran(RUN, null)

        assertEquals(SweepOutcomes.NO_CYCLE, result.outcome)
        assertEquals(RUN.id, result.runId)
        assertFalse(result.signal)
        assertTrue(SweepOutcomes.unscored(result.outcome))
    }

    @Test
    fun `a refused entry keeps its code, has no run and no P&L, and is unscored`() {
        val result = SweepEntryResult.refused("MARKET_COVERAGE_ENDED")

        assertEquals("MARKET_COVERAGE_ENDED", result.outcome)
        assertNull(result.runId)
        assertNull(result.configurationHash)
        assertFalse(result.signal)
        assertEquals(BigDecimal("0E-12"), result.realizedPnl)
        assertEquals(BigDecimal("0E-12"), result.worstCasePnl)
        assertTrue(SweepOutcomes.unscored(result.outcome))
    }

    @Test
    fun `every outcome a sweep run can store is known to the filter`() {
        val known = SweepOutcomes.KNOWN
        assertTrue(known.containsAll(CloseReason.entries.map { it.name } + RejectionReason.entries.map { it.name }))
        assertTrue(known.containsAll(listOf(SweepOutcomes.NO_CYCLE, "MARKET_COVERAGE_ENDED", "SELL_CHECK_NEEDS_TRADES", "SIGNAL_SIZE_NEEDS_TRADES")))
        assertFalse("WINNING" in known)
    }

    private companion object {
        val PAIR: MarketPair = MarketPair.of(MintAddress.of("1".repeat(31) + "2"), MintAddress.WRAPPED_SOL)
        val RUN = RunSummary("run-1", "dataset-1", "dip-paper-v6", "config-hash", "checksum", MintAddress.WRAPPED_SOL, 1, 1, 0, 0, 1,
            BigDecimal.ZERO, Instant.EPOCH, RunStorage.DATASET_REFERENCE)
        val FILL = PaperFill("order-1", Slot.of(1003), Slot.of(1003), "obs-1", BigDecimal("0.0000001"), BigDecimal("1000"),
            BigDecimal("0.00125"), BigDecimal("0.102250000000"), 125, 0, "fixture", ObservationQuality.LAST_TRADE, BigDecimal("0.001"), 19)

        fun position(close: PositionClose?) = Position("position-1", "run-1", "intent-1", PAIR, 1, FILL,
            if (close == null) PositionState.OPEN else PositionState.CLOSED, close, BigDecimal.ONE, BigDecimal.ONE, null, null)

        fun close(reason: CloseReason, pnl: String) = PositionClose(Slot.of(1006), "obs-2", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 125,
            BigDecimal("0.001"), 0, BigDecimal.ONE, BigDecimal(pnl), reason, ExitPriceAssumption.NEXT_TRADE)
    }
}
