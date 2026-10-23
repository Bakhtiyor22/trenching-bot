package com.trenching.bot.portfolio.domain

import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.strategy.domain.SimulationConfig
import java.time.Instant

/**
 * When a take profit or stop loss may sell into a later trade: one at or after [saleSlot] whose block time is at most
 * [deadline], the trigger's time plus spec Section 8's active phase. With no such trade the sale is against the curve standing
 * at [saleSlot], which the recording proves only once it shows that no unseen trade can land within the deadline.
 */
data class ExitSaleWindow(val saleSlot: Slot, val deadline: Instant) {
    /** A recorded trade of the pair, eligible or not, at or after the sale slot and later than the deadline. */
    fun passedBy(observation: MarketObservation): Boolean = observation.slot >= saleSlot && observation.observedTime.isAfter(deadline)

    /** Every later trade lands at or after the coverage end's block time, so a time later than the deadline proves it. */
    fun passedBy(recorded: RecordedThrough): Boolean = recorded.slot >= saleSlot && recorded.time?.isAfter(deadline) == true

    companion object {
        /** Spec Section 8, `exitRetry.activePhaseMs`: engine semantics of dip-paper-v4, not a configuration value. */
        const val ACTIVE_PHASE_MS = 60_000L

        fun of(trigger: ExitTrigger, config: SimulationConfig): ExitSaleWindow {
            val time = requireNotNull(trigger.time) { "Exit trigger without a time observationId=${trigger.observationId}" }
            return ExitSaleWindow(trigger.slot.plus(config.latencySlots), time.plusMillis(ACTIVE_PHASE_MS))
        }
    }
}
