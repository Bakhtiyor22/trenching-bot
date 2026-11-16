package com.trenching.bot.console.automation.domain

/**
 * One history event before it is stored: what happened to an item, as [AutomationItemEvents] derives it from the change. The
 * writer adds the item, the order and the time. [detail] holds the few string facts that belong to one kind only.
 */
data class AutomationItemEventDraft(
    val kind: AutomationItemEventKind,
    val fromState: AutomationItemState? = null,
    val toState: AutomationItemState? = null,
    val step: AutomationStep? = null,
    val attempt: Int? = null,
    val captureSessionId: String? = null,
    val operationId: String? = null,
    val requestsUsed: Int? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val requestId: String? = null,
    val detail: Map<String, String> = emptyMap(),
)
