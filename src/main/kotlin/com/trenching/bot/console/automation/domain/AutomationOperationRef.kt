package com.trenching.bot.console.automation.domain

/**
 * A capture operation an item started for its [step] and [attempt]. Automation cancels it when no item follows it any more:
 * after a manual close abandoned the item, or when the item changed while the operation was being started.
 */
data class AutomationOperationRef(val itemId: String, val captureSessionId: String, val operationId: String, val step: AutomationStep, val attempt: Int) {
    val cancelRequestId: String get() = AutomationRequests.cancel(step, itemId, attempt)
}
