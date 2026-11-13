package com.trenching.bot.console.automation.domain

/** Why a failed item cannot be retried now; the item response and the retry endpoints name it by [code]. */
enum class AutomationRetryRefusal {
    /** The item is not `FAILED`: nothing to retry, or a retry already put it back in capture. */
    ITEM_NOT_FAILED,

    /** The item failed with a code a retry cannot cure: its backfill's budget or a missing block time. */
    RETRY_NOT_ALLOWED,

    /** The item's backfill is still queued or scanning, so none of its items has started capture. */
    BACKFILL_NOT_CAPTURING,

    /** Capture is switched off, so nothing would run the retried step. */
    CAPTURE_DISABLED;

    val code: String get() = name
}
