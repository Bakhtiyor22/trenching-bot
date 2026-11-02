package com.trenching.bot.replay

import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.ExitPriceAssumption
import com.trenching.bot.portfolio.domain.PositionState
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.DatasetCoverage
import com.trenching.bot.replay.domain.ScenarioDecision
import com.trenching.bot.replay.domain.ScenarioResult
import com.trenching.bot.strategy.domain.DipWatchOutcome
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.strategy.domain.TradeIntent
import com.trenching.bot.strategy.domain.WalletSellSignal
import com.trenching.bot.support.DipSample
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * The sample's take-profit cycle (wallet buy at slot 1000, horizon end 5500): rejected at entry for the new reasons, or closed
 * by a wallet sell. Its exit triggers at slot 1005 and sells at 1006; every sample trade lands at the buy's block time, so
 * [late] at slot 1100 is the first trade past the trigger's 60 s.
 */
class ScenarioDecisionTest {
    private val dataset = DipSample.load()
    private val config = SimulationConfig.mvp()
    private val takeProfit = ReplayRunner().run(dataset, config).scenarios.single { it.intent.pair == DipSample.pair(DipSample.TAKE_PROFIT) }
    private val intent = takeProfit.intent
    private val late: MarketObservation = dataset.observations.single { it.id == "fixture:TP:1006:0" }.let {
        it.copy(id = "late:1100", sourceEventId = "late:1100", slot = Slot.of(1100), priceSlot = Slot.of(1100), observedTime = it.observedTime.plusSeconds(61))
    }

    private fun decidedAt(scenario: ScenarioResult): Slot =
        ScenarioDecision.decidedAt(scenario, OrderedMarketData(dataset.observations + late), config, DatasetCoverage.recordedThrough(dataset)(scenario.intent))

    private fun decidedAt(intent: TradeIntent, reason: RejectionReason): Slot =
        decidedAt(ScenarioResult(intent, FillOutcome.Rejected(intent.id, reason), null, null))

    /** The take-profit scenario with its exit triggered by [reason]: closed with [assumption], or left unresolved without one. */
    private fun exitedBy(reason: CloseReason, assumption: ExitPriceAssumption?): ScenarioResult {
        val position = requireNotNull(takeProfit.position)
        val trigger = requireNotNull(position.exitTrigger).copy(reason = reason)
        val close = assumption?.let { requireNotNull(position.close).copy(reason = reason, priceAssumption = it) }
        return takeProfit.copy(position = position.copy(exitTrigger = trigger, close = close,
            state = if (close == null) PositionState.OPEN else PositionState.CLOSED), exitUnavailable = RejectionReason.UNRESOLVED.takeIf { close == null })
    }

    @Test
    fun `a wallet sold before the dip is decided at the wallet's sell`() {
        val sold = intent.copy(watch = DipWatchOutcome.WALLET_SOLD, trigger = null, walletSell = WalletSellSignal("fixture:TP:1001:0", Slot.of(1001)))

        assertEquals(Slot.of(1001), decidedAt(sold, RejectionReason.WALLET_SOLD))
    }

    @Test
    fun `an unavailable reference is decided at the wallet's buy`() {
        val unavailable = intent.copy(watch = DipWatchOutcome.REFERENCE_UNAVAILABLE, trigger = null)

        assertEquals(Slot.of(1000), decidedAt(unavailable, RejectionReason.REFERENCE_UNAVAILABLE))
    }

    @Test
    fun `a wallet-sold exit is decided like a stop loss, at its sale or once its 60 s are proven over`() {
        assertEquals(Slot.of(1006), decidedAt(exitedBy(CloseReason.WALLET_SOLD, ExitPriceAssumption.NEXT_TRADE)))
        assertEquals(Slot.of(1100), decidedAt(exitedBy(CloseReason.WALLET_SOLD, ExitPriceAssumption.STANDING_CURVE)))
        assertEquals(Slot.of(1100), decidedAt(exitedBy(CloseReason.WALLET_SOLD, null)))
        listOf(ExitPriceAssumption.NEXT_TRADE, ExitPriceAssumption.STANDING_CURVE, null).forEach { assumption ->
            assertEquals(decidedAt(exitedBy(CloseReason.STOP_LOSS, assumption)), decidedAt(exitedBy(CloseReason.WALLET_SOLD, assumption)))
        }
    }
}
