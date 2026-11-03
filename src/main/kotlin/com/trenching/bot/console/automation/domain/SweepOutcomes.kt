package com.trenching.bot.console.automation.domain

import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.replay.domain.SellCheckNeedsTradesException
import com.trenching.bot.replay.domain.SignalSizeNeedsTradesException
import com.trenching.bot.replay.domain.VerdictReasons

/**
 * What a sweep run's outcome counts for (plan Rules 21, 22 and 24, with the user's decision of 2026-11-03). An outcome is a
 * close reason, the reason a position stayed open, the reason a cycle bought nothing, [NO_CYCLE], or a refusal code.
 */
object SweepOutcomes {
    /** The run holds no cycle of the item's buy. */
    const val NO_CYCLE = "NO_CYCLE"

    /** The item's registered dataset is no longer in the catalog. */
    const val DATASET_NOT_FOUND = "DATASET_NOT_FOUND"

    /** The item's registered dataset cannot be read as a replay dataset. */
    const val DATASET_INVALID = VerdictReasons.DATASET_INVALID

    /** The codes a sweep entry is refused with before any run is stored; a refusal is final, never retried. */
    val REFUSALS: Set<String> = setOf(VerdictReasons.MARKET_COVERAGE_ENDED, SellCheckNeedsTradesException.CODE, SignalSizeNeedsTradesException.CODE,
        DATASET_NOT_FOUND, DATASET_INVALID)

    /** The buy's size is unknown, so the run cannot say whether it was a signal. */
    private val SIZE_UNKNOWN: Set<String> =
        setOf(RejectionReason.REFERENCE_UNAVAILABLE.name, RejectionReason.SIGNAL_SIZE_UNAVAILABLE.name, NO_CYCLE)

    private val NOT_SIGNALS: Set<String> = SIZE_UNKNOWN + RejectionReason.SIGNAL_BELOW_MIN_SIZE.name + REFUSALS

    /** Every outcome a sweep run can store. */
    val KNOWN: Set<String> = CloseReason.entries.map { it.name }.toSet() + RejectionReason.entries.map { it.name } + NO_CYCLE + REFUSALS

    /** The item's proven first buy is a signal for the entry unless the buy was too small, its size is unknown, or the entry was refused. */
    fun signal(outcome: String): Boolean = outcome !in NOT_SIGNALS

    /** The entry has no usable result on the item: it was refused, or the buy's size is unknown. */
    fun unscored(outcome: String): Boolean = outcome in SIZE_UNKNOWN || outcome in REFUSALS
}
