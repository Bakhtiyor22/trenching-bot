package com.trenching.bot.replay.domain

import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.MarketDataSource
import com.trenching.bot.marketdata.domain.ObservationEligibility
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.strategy.domain.TradeIntent

/**
 * The last slot whose trades a scenario's outcome depends on (Key Decision 2 of the outcome-bounded market capture plan):
 * the close, the fill trade, the first completed curve, the buy itself, or the horizon end for outcomes that need the whole
 * window. Every decision of the dip-entry replay reads only trades up to its own slot, so the outcome over the trades through
 * this slot is the outcome over the whole window.
 */
internal object ScenarioDecision {
    fun decidedAt(scenario: ScenarioResult, source: MarketDataSource, config: SimulationConfig): Slot {
        val intent = scenario.intent
        return when (val outcome = scenario.outcome) {
            is FillOutcome.Rejected -> rejected(outcome.reason, intent, intent.trigger?.slot ?: intent.creationSlot.plus(1), source, config)
            is FillOutcome.Filled -> {
                val position = checkNotNull(scenario.position) { "Filled scenario has no position intentId=${intent.id}" }
                position.close?.slot ?: rejected(checkNotNull(scenario.exitUnavailable) { "Open position has no reason intentId=${intent.id}" },
                    intent, position.fill.actualSlot.plus(1), source, config)
            }
        }
    }

    /** [watchedFrom] is the first slot the phase that gave [reason] read: after the buy, the trigger's slot, or after the fill. */
    private fun rejected(reason: RejectionReason, intent: TradeIntent, watchedFrom: Slot, source: MarketDataSource, config: SimulationConfig): Slot =
        when (reason) {
            RejectionReason.UNSUPPORTED_SUPPLY, RejectionReason.REFERENCE_BELOW_MIN_MARKET_CAP -> intent.creationSlot
            RejectionReason.MIGRATED_BEFORE_ENTRY, RejectionReason.MIGRATED -> checkNotNull(firstCompletion(intent, watchedFrom, source)) {
                "Migration without a completed curve intentId=${intent.id} reason=$reason"
            }
            RejectionReason.NO_FILL_AFTER_TRIGGER -> firstCompletion(intent, watchedFrom, source) ?: intent.horizonEndSlot
            RejectionReason.EXCEEDS_CURVE, RejectionReason.ZERO_QUANTITY -> fillTrade(intent, source, config)
            RejectionReason.NO_DIP, RejectionReason.DIP_BELOW_MIN_MARKET_CAP, RejectionReason.HORIZON_REACHED,
            RejectionReason.NO_EXIT_FILL -> intent.horizonEndSlot
            // The fill model never gives this reason, so it is not known to be decided before the window ends.
            RejectionReason.INVALID_QUALITY -> intent.horizonEndSlot
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
