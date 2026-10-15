package com.trenching.bot.marketdata

import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.MarketObservation

/**
 * Decides whether a window's outcome can still change. It receives the window's canonical observations in
 * `[fromSlot, observedThrough]`, where every selected transaction was captured, and must not depend on anything later.
 */
fun interface MarketCaptureCutoff {
    fun evaluate(window: MarketCaptureWindow, observations: List<MarketObservation>, observedThrough: Slot): CutoffDecision
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
