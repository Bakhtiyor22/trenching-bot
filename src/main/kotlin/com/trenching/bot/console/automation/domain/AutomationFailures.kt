package com.trenching.bot.console.automation.domain

import com.trenching.bot.console.capture.CaptureNames
import com.trenching.bot.console.capture.CaptureStopMeanings
import com.trenching.bot.marketdata.MarketWindowCoverage

/**
 * Failure codes automation sets on an item itself; a failed capture call stores that call's code instead. [FINAL_CAPTURE_CODES]
 * are the capture codes no further attempt can change.
 */
object AutomationFailures {
    const val MISSING_BLOCK_TIME = "MISSING_BLOCK_TIME"

    /** The capture console's code for a deleted capture session or operation; the item's capture can never go on. */
    const val CAPTURE_NOT_FOUND = "CAPTURE_NOT_FOUND"

    /** Every sweep attempt of the item failed unexpectedly. */
    const val SWEEP_FAILED = "SWEEP_FAILED"

    /** A backfill item's capture step would pass its backfill's request budget (plan Rule 39); the item is unscored. */
    const val BACKFILL_BUDGET = "BACKFILL_BUDGET"

    /**
     * The capture console's code for a market window that lists more trades than `market-max-transactions`: none were fetched, and
     * another attempt lists the same trades under the same bound.
     */
    val OVER_CAP: String = MarketWindowCoverage.OVER_CAP.name

    val FINAL_CAPTURE_CODES: Set<String> = setOf(OVER_CAP)

    /** The longest failure message an item stores (plan Rule 17); a longer one is cut. */
    const val MAX_MESSAGE_LENGTH = 512

    fun message(text: String?): String? = text?.take(MAX_MESSAGE_LENGTH)

    /** The clauses for the codes automation sets itself and for the end states that stand in for a code when an operation had none. */
    private val clauses: Map<String, String> = mapOf(
        CAPTURE_NOT_FOUND to "the capture was deleted from the console, so it cannot go on",
        BACKFILL_BUDGET to "the backfill's request budget cannot cover another capture step",
        MISSING_BLOCK_TIME to "the buy has no block time, so it cannot be placed in a trading day or captured",
        SWEEP_FAILED to "every attempt to run the sweep on the captured buy failed unexpectedly",
        CaptureNames.INCOMPLETE to "the capture step ended without finishing and gave no reason",
        CaptureNames.FAILED to "the capture step failed and gave no reason",
        CaptureNames.CANCELLED to "the capture step was cancelled from the console",
        CaptureNames.BLOCKED to "the capture step is blocked by an earlier step",
    )

    /** The clauses that read differently when another attempt follows: [SWEEP_FAILED] says every attempt failed, which a retry has not yet. */
    private val retryClauses: Map<String, String> = mapOf(
        SWEEP_FAILED to "running the sweep on the captured buy failed unexpectedly",
    )

    /** The lower-case clause, with no full stop, that explains [code] in a log sentence; a code the capture console owns is explained by it. */
    fun meaning(code: String): String = clauses[code] ?: CaptureStopMeanings.meaning(code)

    /** The same for an attempt that failed while another one follows. */
    fun retryMeaning(code: String): String = retryClauses[code] ?: meaning(code)
}
