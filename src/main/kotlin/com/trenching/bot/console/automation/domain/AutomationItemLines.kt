package com.trenching.bot.console.automation.domain

/**
 * The log lines of one item change (plan Domain Rules 2–4): which events say something a person reading the journal wants, at which
 * level, in words, with the buy's context. Pure: the writer reads the item once the change is applied, and the component that logs
 * decides when the lines are written.
 *
 * A failed attempt is a retry or the end of the item, which the change shows by also moving the item to `FAILED`; either way it is
 * one line, so the state change that ends the item says nothing more once an attempt explains it.
 */
object AutomationItemLines {
    private const val UNKNOWN_CODE = "UNKNOWN"
    private const val STEP = "step"
    private const val STATE = "state"
    private const val CODE = "code"
    private const val ATTEMPT = "attempt"
    private const val REQUESTS_USED = "requestsUsed"
    private const val REASON_KEY = "reason"
    private const val REPLACED_BY_KEY = "replacedBy"
    private const val BACKFILL_ID_KEY = "backfillId"

    fun of(subject: AutomationItemSubject, change: AutomationItemChange, calendar: TradingCalendar, maxAttempts: Int): List<AutomationItemLogLine> =
        Change(subject, change, calendar, maxAttempts).lines()

    private class Change(
        private val subject: AutomationItemSubject,
        private val change: AutomationItemChange,
        private val calendar: TradingCalendar,
        private val maxAttempts: Int,
    ) {
        private val endsItem = change.events.any { it.kind == AutomationItemEventKind.STATE_CHANGED && it.toState == AutomationItemState.FAILED }
        private val hasFailedAttempt = change.events.any { it.kind == AutomationItemEventKind.ATTEMPT_FAILED }

        fun lines(): List<AutomationItemLogLine> = change.events.flatMap(::lines)

        private fun lines(draft: AutomationItemEventDraft): List<AutomationItemLogLine> = when (draft.kind) {
            AutomationItemEventKind.HISTORY_STARTED ->
                listOf(debug("Item history started.", STATE to draft.toState, STEP to stepName(draft.step)))
            AutomationItemEventKind.STATE_CHANGED -> stateChanged(draft)
            AutomationItemEventKind.OPERATION_STARTED ->
                listOf(debug("Capture step started.", STEP to stepName(draft.step), ATTEMPT to attempt(draft.attempt), "operationId" to draft.operationId))
            AutomationItemEventKind.OPERATION_ENDED -> listOf(debug("Capture step ended.", STEP to stepName(draft.step), ATTEMPT to attempt(draft.attempt),
                "operationState" to draft.detail["operationState"], "requests" to draft.requestsUsed, "operationId" to draft.operationId))
            AutomationItemEventKind.ATTEMPT_FAILED -> listOf(attemptFailed(draft))
            AutomationItemEventKind.RETRY_REQUESTED -> listOf(line(AutomationLogLevel.INFO, "Buy retried from the console.",
                listOf(CODE to change.before?.progress?.failureCode, STEP to stepName(draft.step), "requestId" to draft.requestId), AutomationActivityKind.RETRIED_BY_PERSON))
            AutomationItemEventKind.SWEEP_ENTRY_RECORDED -> listOf(debug("Sweep entry recorded.", "planId" to draft.detail["planId"], "label" to draft.detail["label"],
                "runId" to draft.detail["runId"], "outcome" to draft.detail["outcome"]))
            AutomationItemEventKind.REPLACED -> listOf(line(replacedLevel(), "Buy replaced: an earlier buy of its token was found, so that buy takes its place.",
                listOf(STATE to draft.fromState, REPLACED_BY_KEY to draft.detail[REPLACED_BY_KEY])))
        }

        private fun stateChanged(draft: AutomationItemEventDraft): List<AutomationItemLogLine> {
            if (draft.fromState == null) return found(draft)
            if (draft.detail[REASON_KEY] == AutomationItemEvents.RETAKEN_BY_BACKFILL) {
                return listOf(line(replacedLevel(), "Buy replaced: a backfill found the same buy again and stored it afresh.",
                    listOf(STATE to draft.fromState, "retakenBy" to draft.detail[BACKFILL_ID_KEY])))
            }
            return when (draft.toState ?: return listOf(movedOn(draft))) {
                AutomationItemState.SKIPPED_NOT_FIRST_BUY -> listOf(line(AutomationLogLevel.INFO, "Buy skipped: {}.", listOf("classification" to subject.classification),
                    AutomationActivityKind.SKIPPED, arguments = listOf(AutomationFirstBuy.meaning(subject.classification))))
                AutomationItemState.CAPTURED -> listOf(line(AutomationLogLevel.INFO, "Buy captured and registered as a dataset; the sweep runs next.",
                    listOf("datasetId" to subject.datasetId, REQUESTS_USED to subject.requestsUsed), AutomationActivityKind.CAPTURED))
                AutomationItemState.SIMULATED -> listOf(line(AutomationLogLevel.INFO, "Buy simulated: every sweep configuration ran on it.",
                    listOf("runs" to subject.sweepRuns, "refused" to subject.sweepRefused?.takeIf { it > 0 }), AutomationActivityKind.SIMULATED))
                AutomationItemState.ABANDONED ->
                    listOf(line(AutomationLogLevel.INFO, "Buy abandoned: its session was closed by hand.", emptyList(), AutomationActivityKind.ABANDONED))
                AutomationItemState.FAILED -> if (hasFailedAttempt) emptyList() else listOf(failed(draft))
                AutomationItemState.WAITING ->
                    listOf(debug("Buy waits for the market delay before its market step.", STATE to draft.toState, STEP to stepName(draft.step)))
                AutomationItemState.BLOCKED_BUDGET ->
                    listOf(debug("Buy is held: the request cap leaves no room for its next step.", STATE to draft.toState, STEP to stepName(draft.step)))
                AutomationItemState.DISCOVERED, AutomationItemState.CAPTURING ->
                    listOf(if (draft.fromState == AutomationItemState.DISCOVERED && draft.captureSessionId != null) sessionCreated(draft) else movedOn(draft))
            }
        }

        /** A backfill replaces buys by the hundred while it scans; the buys of a live night are few and worth a line. */
        private fun replacedLevel(): AutomationLogLevel = if (subject.backfillId != null) AutomationLogLevel.DEBUG else AutomationLogLevel.INFO

        private fun found(draft: AutomationItemEventDraft): List<AutomationItemLogLine> {
            val buyAt = subject.blockTime
            val isBackfill = subject.backfillId != null
            val foundLine = line(if (isBackfill) AutomationLogLevel.DEBUG else AutomationLogLevel.INFO, "Buy found.",
                listOf("buyAt" to buyAt, "tradingDay" to buyAt?.let { calendar.dayOf(it).date }, "sessionId" to subject.sessionId),
                if (isBackfill) AutomationActivityKind.BACKFILL_FOUND else AutomationActivityKind.LIVE_FOUND)
            return if (draft.toState == AutomationItemState.FAILED) listOf(foundLine, failed(draft)) else listOf(foundLine)
        }

        private fun sessionCreated(draft: AutomationItemEventDraft) =
            debug("Capture session created.", STEP to stepName(draft.step), "captureSessionId" to draft.captureSessionId)

        private fun movedOn(draft: AutomationItemEventDraft) =
            debug("Buy moved on to its next step.", STATE to draft.toState, STEP to stepName(draft.step), ATTEMPT to attempt(draft.attempt))

        private fun attemptFailed(draft: AutomationItemEventDraft): AutomationItemLogLine {
            if (endsItem) return failed(draft)
            val code = draft.failureCode ?: UNKNOWN_CODE
            return line(AutomationLogLevel.WARN, "Buy capture will retry the {} step: {}.",
                listOf(CODE to code, STEP to stepName(draft.step), ATTEMPT to attempt(draft.attempt)), AutomationActivityKind.RETRIED_ATTEMPT, code,
                listOf(stepWords(draft.step), AutomationFailures.retryMeaning(code)))
        }

        private fun failed(draft: AutomationItemEventDraft): AutomationItemLogLine {
            val code = draft.failureCode ?: UNKNOWN_CODE
            val meaning = AutomationFailures.meaning(code)
            val fields = listOf(CODE to code, STEP to stepName(draft.step), ATTEMPT to attempt(draft.attempt), REQUESTS_USED to subject.requestsUsed)
            // A backfill's budget stops its items one after another; the pipeline says once, as a WARN, that the budget is spent.
            val level = if (code == AutomationFailures.BACKFILL_BUDGET) AutomationLogLevel.DEBUG else AutomationLogLevel.WARN
            val step = draft.step ?: return line(level, "Buy failed before capture started and will not be retried: {}.", fields,
                AutomationActivityKind.FAILED, code, listOf(meaning))
            return line(level, "Buy failed at the {} step and will not be retried: {}.", fields, AutomationActivityKind.FAILED, code,
                listOf(stepWords(step), meaning))
        }

        private fun debug(sentence: String, vararg fields: Pair<String, Any?>) = line(AutomationLogLevel.DEBUG, sentence, fields.toList())

        private fun line(
            level: AutomationLogLevel,
            format: String,
            fields: List<Pair<String, Any?>>,
            activity: AutomationActivityKind? = null,
            code: String? = null,
            arguments: List<Any> = emptyList(),
        ): AutomationItemLogLine {
            val context = listOf("itemId" to subject.itemId, "mint" to subject.mint, "wallet" to subject.wallet, BACKFILL_ID_KEY to subject.backfillId)
            val present = (fields + context).mapNotNull { (key, value) -> value?.let { key to it } }
            return AutomationItemLogLine(level, format, arguments, present, activity, code)
        }

        private fun stepName(step: AutomationStep?): String? = step?.name?.lowercase()

        private fun stepWords(step: AutomationStep?): String = step?.name?.lowercase()?.replace('_', ' ').orEmpty()

        private fun attempt(attempt: Int?): String? = attempt?.let { "$it/$maxAttempts" }
    }
}
