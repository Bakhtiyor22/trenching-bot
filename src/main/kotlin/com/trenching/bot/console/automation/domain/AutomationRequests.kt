package com.trenching.bot.console.automation.domain

/** Request ids follow the capture console's id rule, so one can be reused as a capture request id. */
object AutomationRequests {
    private val REQUEST_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}")
    private const val CAPTURE_PREFIX = "auto"
    private const val CANCEL_PREFIX = "cancel"
    private const val ITEM_ID_CHARACTERS = 24

    fun requireRequestId(requestId: String?): String =
        requestId?.takeIf(REQUEST_ID::matches) ?: throw InvalidAutomationRequestException("requestId", "must match ${REQUEST_ID.pattern}")

    /**
     * Plan Rule 14: the request id of an item's capture call, `auto-<step>-<first 24 hex of the item id>-<attempt>`. It is the
     * same after a restart, so repeating the call returns the session or operation the first one made.
     */
    fun capture(step: AutomationStep, itemId: String, attempt: Int): String =
        "$CAPTURE_PREFIX-${step.name.lowercase()}-${itemId.take(ITEM_ID_CHARACTERS)}-$attempt"

    /** The request id that cancels the operation an item started for [step] and [attempt]; a repeat is the same control. */
    fun cancel(step: AutomationStep, itemId: String, attempt: Int): String =
        "$CAPTURE_PREFIX-$CANCEL_PREFIX-${step.name.lowercase()}-${itemId.take(ITEM_ID_CHARACTERS)}-$attempt"
}
