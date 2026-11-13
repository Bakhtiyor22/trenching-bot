package com.trenching.bot.console.automation.domain

import com.trenching.bot.chaincapture.Sha256

/** Request ids follow the capture console's id rule, so one can be reused as a capture request id. */
object AutomationRequests {
    private val REQUEST_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}")
    private const val CAPTURE_PREFIX = "auto"
    private const val CANCEL_PREFIX = "cancel"
    private const val ITEM_ID_CHARACTERS = 24
    private const val MAX_LENGTH = 80
    private const val HASH_CHARACTERS = 32

    fun requireRequestId(requestId: String?): String =
        requestId?.takeIf(REQUEST_ID::matches) ?: throw InvalidAutomationRequestException("requestId", "must match ${REQUEST_ID.pattern}")

    /**
     * Plan Rule 14: the request id of an item's capture call, `auto-<step>-<first 24 hex of the item id>-<attempt>`. It is the
     * same after a restart, so repeating the call returns the session or operation the first one made.
     */
    fun capture(step: AutomationStep, itemId: String, attempt: Int, retries: Int): String =
        "$CAPTURE_PREFIX-${step.name.lowercase()}-${itemId.take(ITEM_ID_CHARACTERS)}-${attemptPart(attempt, retries)}"

    /** The request id that cancels the operation an item started for [step] and [attempt]; a repeat is the same control. */
    fun cancel(step: AutomationStep, itemId: String, attempt: Int, retries: Int): String =
        "$CAPTURE_PREFIX-$CANCEL_PREFIX-${step.name.lowercase()}-${itemId.take(ITEM_ID_CHARACTERS)}-${attemptPart(attempt, retries)}"

    /**
     * The request id of one item's retry within a day retry: `<day request id>-<first 24 hex of the item id>`. When that is longer than
     * the id rule allows, the day request id is replaced by the first 32 hex of its SHA-256, so two long ids never share a result.
     */
    fun dayItem(dayRequestId: String, itemId: String): String {
        val suffix = itemId.take(ITEM_ID_CHARACTERS)
        val readable = "$dayRequestId-$suffix"
        return if (readable.length <= MAX_LENGTH) readable else "${Sha256.hex(dayRequestId.toByteArray()).take(HASH_CHARACTERS)}-$suffix"
    }

    /** `<attempt>` for an item never retried, so its ids stay what they were; `r<retries>-<attempt>` once a person has retried it. */
    private fun attemptPart(attempt: Int, retries: Int): String = if (retries > 0) "r$retries-$attempt" else "$attempt"
}
