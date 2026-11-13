package com.trenching.bot.console.automation.domain

/** What one item's retry did once it committed. A refusal is [AutomationRetryRefusedException], not a result. */
sealed interface AutomationRetryResult {
    /** The item went back into capture or the sweep; [previousFailure] is the failure it had. */
    data class Retried(
        val itemId: String,
        val step: AutomationStep?,
        val previousFailure: String?,
        val retries: Int,
        val requestId: String,
    ) : AutomationRetryResult

    /** The request id is the one of the item's last retry: that retry already happened and nothing changed now. */
    data object Unchanged : AutomationRetryResult
}
