package com.trenching.bot.marketdata

import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.MarketObservation
import java.time.Instant

/**
 * Decides whether a window's outcome can still change. It receives the window's canonical observations in
 * `[fromSlot, observedThrough]`, where every selected transaction was captured, and must not depend on anything later.
 * `observedThrough` is never before the window's first buy; a decision is accepted only from that slot on, never in a lead.
 *
 * [observedThroughTime] reads the block time of the nearest produced block at or below `observedThrough` on demand, so a
 * cutoff calls it only when its decision waits on time. It answers null when the time is unavailable or the capture records
 * no block times.
 */
fun interface MarketCaptureCutoff {
    fun evaluate(
        window: MarketCaptureWindow,
        observations: List<MarketObservation>,
        observedThrough: Slot,
        observedThroughTime: () -> Instant?,
    ): CutoffDecision
}

sealed interface CutoffDecision {
    data object Undecided : CutoffDecision

    /** The outcome depends on no trade after [slot], which is at most the evaluated `observedThrough`. */
    data class Decided(val slot: Slot, val outcome: String) : CutoffDecision
}

/** Which evaluator decided a window; a decided window is reopened when a later capture asks with another identity. */
data class CutoffIdentity(val engineVersion: String, val configurationHash: String)

enum class MarketCutoffMode {
    /** Every selected transaction of every window is fetched. */
    FULL,

    /** A window stops fetching once its cutoff decides; its later transactions become `NOT_NEEDED`. */
    DECIDED,
}
