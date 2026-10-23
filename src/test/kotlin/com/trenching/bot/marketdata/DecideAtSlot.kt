package com.trenching.bot.marketdata

import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.MarketObservation
import java.time.Instant

/** A causal fake cutoff: decides at [slot] once the evaluated prefix reaches it, and records every evaluation; it never asks for a time. */
internal class DecideAtSlot(private val slot: Long, private val outcome: String = "TAKE_PROFIT") : MarketCaptureCutoff {
    data class Evaluation(val observedThrough: Long, val observationSlots: List<Long>)

    val evaluations = mutableListOf<Evaluation>()

    override fun evaluate(
        window: MarketCaptureWindow,
        observations: List<MarketObservation>,
        observedThrough: Slot,
        observedThroughTime: () -> Instant?,
    ): CutoffDecision {
        evaluations += Evaluation(observedThrough.value, observations.map { it.slot.value })
        return if (observedThrough.value >= slot) CutoffDecision.Decided(Slot.of(slot), outcome) else CutoffDecision.Undecided
    }
}
