package com.trenching.bot.console.automation.domain

/**
 * Whether a person may retry an item now. The first refusal that applies wins, in the order of [AutomationRetryRefusal]; null
 * means the retry is allowed. A retry bypasses nothing else: the request cap, the market delay and the pipeline's order apply to
 * the retried step as to any other. A backfill's item is retried in any backfill state but the scan's own (`QUEUED`, `SCANNING`): a
 * retry never changes the backfill, so an ended one keeps its coverage.
 */
object AutomationRetryRule {
    private val UNRETRYABLE_FAILURES: Set<String> = setOf(AutomationFailures.BACKFILL_BUDGET, AutomationFailures.MISSING_BLOCK_TIME)

    /** [backfillId] is the item's backfill, null for a live item, and [backfillState] that backfill's state. */
    fun refusal(progress: AutomationItemProgress, backfillId: String?, backfillState: BackfillState?, captureEnabled: Boolean): AutomationRetryRefusal? = when {
        progress.state != AutomationItemState.FAILED -> AutomationRetryRefusal.ITEM_NOT_FAILED
        progress.failureCode in UNRETRYABLE_FAILURES -> AutomationRetryRefusal.RETRY_NOT_ALLOWED
        backfillId != null && (backfillState == null || backfillState in BackfillState.SCANNING_STATES) -> AutomationRetryRefusal.BACKFILL_NOT_CAPTURING
        !captureEnabled -> AutomationRetryRefusal.CAPTURE_DISABLED
        else -> null
    }
}
