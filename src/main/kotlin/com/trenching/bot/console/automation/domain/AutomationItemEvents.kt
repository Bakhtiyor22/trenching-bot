package com.trenching.bot.console.automation.domain

/**
 * The history events an item's changes produce (plan Domain Rules H1 and H5). Pure: each function reads the progress before and
 * after one change and returns its events in the order they happened; the writer stores them in the change's transaction.
 */
object AutomationItemEvents {
    /** The longest value a detail entry keeps, which keeps a detail far below its column's 4 KB. */
    const val MAX_DETAIL_VALUE_LENGTH = 256

    /** The reason an item's own buy, found again by another backfill, ends the item's history under that id. */
    const val RETAKEN_BY_BACKFILL = "RETAKEN_BY_BACKFILL"

    /**
     * A buy found: no state before it. A buy without a block time is found already failed, with its code. [replaces] is the item of
     * another id this one took the place of, when it did.
     */
    fun created(progress: AutomationItemProgress, replaces: String? = null): List<AutomationItemEventDraft> {
        val failed = progress.state == AutomationItemState.FAILED
        return listOf(AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, toState = progress.state, failureCode = progress.failureCode.takeIf { failed },
            detail = detail("replaces" to replaces)))
    }

    /** The last event of an item an earlier buy replaced: [progress] is where it stood, [replacedBy] the item that took its place. */
    fun replaced(progress: AutomationItemProgress, replacedBy: String): List<AutomationItemEventDraft> = listOf(
        AutomationItemEventDraft(AutomationItemEventKind.REPLACED, fromState = progress.state, step = progress.step, detail = detail("replacedBy" to replacedBy)),
    )

    /** The last event of an item that [backfillId] found again, which stored the same buy afresh under the same id. */
    fun retaken(progress: AutomationItemProgress, backfillId: String?): List<AutomationItemEventDraft> = listOf(
        AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, fromState = progress.state, step = progress.step,
            detail = detail("reason" to RETAKEN_BY_BACKFILL, "backfillId" to backfillId)),
    )

    /**
     * The events of one pipeline, sweep or close change from [before] to [after], oldest first: the end of the operation the item
     * followed, a failed attempt, the state or step change, the start of an operation. [operationRequests] is what the operation
     * that ended used, when known. A change that touches none of the recorded fields has no events.
     */
    fun changed(before: AutomationItemProgress, after: AutomationItemProgress, trace: AutomationItemTrace, operationRequests: Int?): List<AutomationItemEventDraft> {
        val events = mutableListOf<AutomationItemEventDraft>()
        trace.endedOperationState?.let { state ->
            events += AutomationItemEventDraft(AutomationItemEventKind.OPERATION_ENDED, step = before.currentStep, attempt = before.currentAttempt,
                captureSessionId = before.captureSessionId, operationId = before.operationId, requestsUsed = operationRequests,
                detail = detail("operationState" to state))
        }
        if (trace.attemptFailed) {
            events += AutomationItemEventDraft(AutomationItemEventKind.ATTEMPT_FAILED, step = after.step, attempt = failedAttempt(after),
                captureSessionId = after.captureSessionId, operationId = before.operationId, failureCode = after.failureCode, failureMessage = after.failureMessage)
        }
        if (before.state != after.state || before.step != after.step) {
            val failure = after.state == AutomationItemState.FAILED && !trace.attemptFailed
            events += AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, fromState = before.state, toState = after.state, step = after.step,
                attempt = after.step?.let { after.stepAttempt }, captureSessionId = after.captureSessionId, operationId = after.operationId,
                failureCode = after.failureCode.takeIf { failure }, failureMessage = after.failureMessage.takeIf { failure })
        }
        if (before.operationId == null && after.operationId != null) {
            events += AutomationItemEventDraft(AutomationItemEventKind.OPERATION_STARTED, step = after.step, attempt = after.stepAttempt,
                captureSessionId = after.captureSessionId, operationId = after.operationId)
        }
        return events
    }

    /** An item a person's close abandoned: the state change, under the [requestId] of the close. */
    fun closed(before: AutomationItemProgress, after: AutomationItemProgress, requestId: String): List<AutomationItemEventDraft> =
        changed(before, after, AutomationItemTrace.NONE, null).map { it.copy(requestId = requestId) }

    /** A person's retry of the failed item: one event, which names the state and step it went back to and leaves the failure to the events that recorded it. */
    fun retried(before: AutomationItemProgress, after: AutomationItemProgress, requestId: String): List<AutomationItemEventDraft> = listOf(
        AutomationItemEventDraft(AutomationItemEventKind.RETRY_REQUESTED, fromState = before.state, toState = after.state, step = after.step,
            attempt = after.step?.let { after.stepAttempt }, captureSessionId = after.captureSessionId, requestId = requestId),
    )

    /** One sweep entry stored for an item that stands at [progress]. */
    fun sweepEntry(progress: AutomationItemProgress, planId: String, label: String, runId: String?, outcome: String): List<AutomationItemEventDraft> = listOf(
        AutomationItemEventDraft(AutomationItemEventKind.SWEEP_ENTRY_RECORDED, step = AutomationStep.SWEEP, attempt = progress.sweepAttempt,
            detail = detail("planId" to planId, "label" to label, "runId" to runId, "outcome" to outcome)),
    )

    /** The state of an item that existed before its history was recorded; [requestsUsed] is its capture's total so far. */
    fun historyStarted(progress: AutomationItemProgress, requestsUsed: Int?): List<AutomationItemEventDraft> = listOf(
        AutomationItemEventDraft(AutomationItemEventKind.HISTORY_STARTED, toState = progress.state, step = progress.step,
            attempt = progress.step?.let { progress.stepAttempt }, captureSessionId = progress.captureSessionId, operationId = progress.operationId,
            requestsUsed = requestsUsed, failureCode = progress.failureCode, failureMessage = progress.failureMessage),
    )

    /** The attempt that failed: a failed attempt that runs again has already moved the item to the next one. */
    private fun failedAttempt(after: AutomationItemProgress): Int =
        if (after.state == AutomationItemState.FAILED) after.stepAttempt else after.stepAttempt - 1

    private fun detail(vararg entries: Pair<String, String?>): Map<String, String> =
        entries.mapNotNull { (key, value) -> value?.let { key to it.take(MAX_DETAIL_VALUE_LENGTH) } }.toMap()
}
