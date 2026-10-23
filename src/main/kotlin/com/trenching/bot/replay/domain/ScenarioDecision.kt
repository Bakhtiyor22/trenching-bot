package com.trenching.bot.replay.domain

import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.MarketDataSource
import com.trenching.bot.marketdata.domain.ObservationEligibility
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.ExitPriceAssumption
import com.trenching.bot.portfolio.domain.ExitSaleWindow
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.portfolio.domain.PositionClose
import com.trenching.bot.portfolio.domain.RecordedThrough
import com.trenching.bot.strategy.domain.DipWindow
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.strategy.domain.TradeIntent

/**
 * The last slot whose trades a scenario's outcome depends on (Key Decision 2 of the outcome-bounded market capture plan):
 * the close, the fill trade, the first completed curve, the buy itself, the first trade after a dip window, a failed sale's
 * attempt, or the horizon end for outcomes that need the whole window. A time exit against the standing curve is decided at
 * its close slot, the slot proving its start is covered. A take profit or stop loss sold against the standing curve, or left
 * unresolved, is decided at the slot proving no trade can land within its 60 s ([ExitSaleWindow]): the first recorded trade
 * past them, or else the end of [RecordedThrough], which proved them. Every decision of the dip-entry replay reads only trades
 * up to its own slot, so the outcome over the trades through this slot is the outcome over the whole window.
 */
internal object ScenarioDecision {
    /** [recorded] is how far the scenario's dataset proves its pair recorded, as the replay judged its exit. */
    fun decidedAt(scenario: ScenarioResult, source: MarketDataSource, config: SimulationConfig, recorded: RecordedThrough): Slot {
        val intent = scenario.intent
        return when (val outcome = scenario.outcome) {
            is FillOutcome.Rejected -> rejected(outcome.reason, intent, null, intent.trigger?.slot ?: intent.creationSlot.plus(1), source,
                config, recorded)
            is FillOutcome.Filled -> {
                val position = checkNotNull(scenario.position) { "Filled scenario has no position intentId=${intent.id}" }
                position.close?.let { closedAt(it, position, intent, source, config, recorded) }
                    ?: rejected(checkNotNull(scenario.exitUnavailable) { "Open position has no reason intentId=${intent.id}" },
                        intent, position, position.fill.actualSlot, source, config, recorded)
            }
        }
    }

    private fun closedAt(
        close: PositionClose,
        position: Position,
        intent: TradeIntent,
        source: MarketDataSource,
        config: SimulationConfig,
        recorded: RecordedThrough,
    ): Slot = when (close.priceAssumption) {
        ExitPriceAssumption.NEXT_TRADE -> close.slot
        ExitPriceAssumption.STANDING_CURVE -> when (close.reason) {
            CloseReason.TIME_EXIT -> close.slot
            CloseReason.TAKE_PROFIT, CloseReason.STOP_LOSS -> deadlinePassedAt(position, intent, source, config, recorded)
        }
    }

    /**
     * [watchedFrom] is the first slot the phase that gave [reason] read: after the buy, the trigger's slot, or the fill's own
     * slot, whose later trades a position already holds through. [position] is the open position an exit reason explains, null
     * for an entry rejection.
     */
    private fun rejected(
        reason: RejectionReason,
        intent: TradeIntent,
        position: Position?,
        watchedFrom: Slot,
        source: MarketDataSource,
        config: SimulationConfig,
        recorded: RecordedThrough,
    ): Slot =
        when (reason) {
            RejectionReason.UNSUPPORTED_SUPPLY, RejectionReason.REFERENCE_BELOW_MIN_MARKET_CAP -> intent.creationSlot
            RejectionReason.MIGRATED_BEFORE_ENTRY, RejectionReason.MIGRATED -> checkNotNull(firstCompletion(intent, watchedFrom, source)) {
                "Migration without a completed curve intentId=${intent.id} reason=$reason"
            }
            RejectionReason.NO_FILL_AFTER_TRIGGER -> firstCompletion(intent, watchedFrom, source) ?: intent.horizonEndSlot
            RejectionReason.EXCEEDS_CURVE, RejectionReason.ZERO_QUANTITY -> fillTrade(intent, source, config)
            RejectionReason.NO_DIP -> noDip(intent, source, config)
            RejectionReason.DIP_BELOW_MIN_MARKET_CAP, RejectionReason.HORIZON_REACHED, RejectionReason.NO_EXIT_FILL -> intent.horizonEndSlot
            RejectionReason.UNRESOLVED -> unresolved(checkNotNull(position) { "Unresolved exit without a position intentId=${intent.id}" },
                intent, source, config, recorded)
            // The fill model never gives this reason, so it is not known to be decided before the window ends.
            RejectionReason.INVALID_QUALITY -> intent.horizonEndSlot
        }

    /** A windowed watch ends at its first trade outside the window, and one without a block time to count from never starts. */
    private fun noDip(intent: TradeIntent, source: MarketDataSource, config: SimulationConfig): Slot =
        when (val window = DipWindow.of(config, intent.t0)) {
            DipWindow.Unbounded -> intent.horizonEndSlot
            DipWindow.Closed -> intent.creationSlot
            is DipWindow.Until -> source.observations(intent.pair, intent.creationSlot.plus(1), intent.horizonEndSlot)
                .firstOrNull { !window.admits(it.observedTime) }?.slot ?: intent.horizonEndSlot
        }

    /**
     * Where the failed sale was decided: a time exit tried only the curve standing at its trigger's slot. A take profit or stop
     * loss tried the trades within its 60 s, or the standing curve without one, and is decided once they are proven over.
     */
    private fun unresolved(position: Position, intent: TradeIntent, source: MarketDataSource, config: SimulationConfig, recorded: RecordedThrough): Slot {
        val trigger = checkNotNull(position.exitTrigger) { "Unresolved exit without a trigger positionId=${position.id}" }
        return when (trigger.reason) {
            CloseReason.TIME_EXIT -> trigger.slot
            CloseReason.TAKE_PROFIT, CloseReason.STOP_LOSS -> deadlinePassedAt(position, intent, source, config, recorded)
        }
    }

    /** The first recorded trade past the trigger's 60 s, or the end of [recorded] when only its time proves them over. */
    private fun deadlinePassedAt(
        position: Position,
        intent: TradeIntent,
        source: MarketDataSource,
        config: SimulationConfig,
        recorded: RecordedThrough,
    ): Slot {
        val window = ExitSaleWindow.of(checkNotNull(position.exitTrigger) { "Exit without a trigger positionId=${position.id}" }, config)
        val passedByTrade = source.observations(intent.pair, window.saleSlot, intent.horizonEndSlot).firstOrNull(window::passedBy)?.slot
        return passedByTrade ?: checkNotNull(recorded.slot.takeIf { window.passedBy(recorded) }) {
            "Exit decided without its 60 s proven over positionId=${position.id}"
        }
    }

    private fun firstCompletion(intent: TradeIntent, from: Slot, source: MarketDataSource): Slot? =
        source.observations(intent.pair, from, intent.horizonEndSlot).firstOrNull { it.curveComplete }?.slot

    /** The trade `FillModel` bought against: the first eligible one at least `latencySlots` after the trigger, before the curve completes. */
    private fun fillTrade(intent: TradeIntent, source: MarketDataSource, config: SimulationConfig): Slot {
        val trigger = checkNotNull(intent.trigger) { "Curve rejection without a trigger intentId=${intent.id}" }
        val earliest = trigger.slot.plus(config.latencySlots)
        val trade = source.observations(intent.pair, trigger.slot, intent.horizonEndSlot).asSequence()
            .takeWhile { !it.curveComplete }
            .firstOrNull { it.slot >= earliest && ObservationEligibility.issue(it, config.maxAgeSlots) == null }
        return checkNotNull(trade) { "Curve rejection without a fill trade intentId=${intent.id}" }.slot
    }
}
