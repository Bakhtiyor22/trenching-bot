package com.trenching.bot.console.automation.domain

import java.time.Instant

/**
 * Where one item stands in its capture (plan Rules 13–18): its [state], the [step] it is on and that step's [stepAttempt], its
 * capture session, the current step's operation once started, and what the capture produced. A discovered item has no step
 * yet; it starts with [AutomationStep.CREATE_SESSION], attempt 1. Each function returns the progress after one event; the
 * pipeline stores it only when [AutomationItemTransitions] allows the state change.
 */
data class AutomationItemProgress(
    val state: AutomationItemState,
    val step: AutomationStep?,
    val stepAttempt: Int,
    val captureSessionId: String?,
    val operationId: String?,
    val datasetId: String?,
    val classification: String?,
    val failureCode: String?,
    val failureMessage: String?,
    val nextActionAt: Instant?,
) {
    val currentStep: AutomationStep get() = step ?: AutomationStep.CREATE_SESSION

    val currentAttempt: Int get() = stepAttempt.coerceAtLeast(FIRST_ATTEMPT)

    val onFirstAttempt: Boolean get() = currentAttempt == FIRST_ATTEMPT

    /** Whether the pipeline acts on the item at [now]: it is in capture, follows no operation and its next action has come. */
    fun dueAt(now: Instant): Boolean =
        state in AutomationItemState.IN_CAPTURE && operationId == null && nextActionAt?.isAfter(now) != true

    /** The capture session exists; the wallet step comes next. */
    fun sessionCreated(captureSessionId: String, now: Instant): AutomationItemProgress =
        copy(captureSessionId = captureSessionId).succeeded(now, now, classification)

    /** The current step succeeded with the capture's latest [classification]; the next step starts at [nextStartsAt]. */
    fun succeeded(nextStartsAt: Instant, now: Instant, classification: String?): AutomationItemProgress = copy(
        state = if (nextStartsAt.isAfter(now)) AutomationItemState.WAITING else AutomationItemState.CAPTURING,
        step = currentStep.next(),
        stepAttempt = FIRST_ATTEMPT,
        operationId = null,
        classification = classification,
        failureCode = null,
        failureMessage = null,
        nextActionAt = nextStartsAt,
    )

    /**
     * The current step failed with [code]: it runs again now under the next attempt or, once [maxAttempts] are spent, the item
     * fails and keeps the failed operation for its audit.
     */
    fun failed(code: String, message: String?, maxAttempts: Int, now: Instant): AutomationItemProgress {
        val failure = copy(step = currentStep, failureCode = code, failureMessage = AutomationFailures.message(message))
        return if (currentAttempt < maxAttempts) {
            failure.copy(state = AutomationItemState.CAPTURING, stepAttempt = currentAttempt + 1, operationId = null, nextActionAt = now)
        } else {
            failure.copy(state = AutomationItemState.FAILED, stepAttempt = currentAttempt, nextActionAt = null)
        }
    }

    /** The request cap leaves no room for the current step's operation; the item is checked again on every tick. */
    fun blocked(): AutomationItemProgress = onCurrentStep(AutomationItemState.BLOCKED_BUDGET).copy(nextActionAt = null)

    /** The item's capture is gone from the capture console, so no attempt can succeed: the item fails at once with [code]. */
    fun lost(code: String, message: String?): AutomationItemProgress = onCurrentStep(AutomationItemState.FAILED)
        .copy(failureCode = code, failureMessage = AutomationFailures.message(message), nextActionAt = null)

    /** The sweep attempt in progress: the first until a sweep has failed. */
    val sweepAttempt: Int get() = if (step == AutomationStep.SWEEP) currentAttempt else FIRST_ATTEMPT

    /**
     * A sweep of the captured item failed with [code]: it is swept again under the next attempt once
     * [AutomationLimits.SWEEP_RETRY_DELAY], doubled for each earlier failure, has passed; once [maxAttempts] are spent, the item
     * fails.
     */
    fun sweepFailed(code: String, message: String?, maxAttempts: Int, now: Instant): AutomationItemProgress {
        val failure = copy(step = AutomationStep.SWEEP, failureCode = code, failureMessage = AutomationFailures.message(message))
        val attempt = sweepAttempt
        return if (attempt < maxAttempts) {
            val delay = AutomationLimits.SWEEP_RETRY_DELAY.multipliedBy(1L shl (attempt - FIRST_ATTEMPT))
            failure.copy(state = AutomationItemState.CAPTURED, stepAttempt = attempt + 1, nextActionAt = now.plus(delay))
        } else {
            failure.copy(state = AutomationItemState.FAILED, stepAttempt = attempt, nextActionAt = null)
        }
    }

    private companion object {
        const val FIRST_ATTEMPT = 1
    }
}

// The one-argument transitions are extensions rather than members, so mappers do not take them for fluent setters.

/** The current step's capture call was made; its operation is followed on every tick until it ends. */
fun AutomationItemProgress.started(operationId: String): AutomationItemProgress =
    onCurrentStep(AutomationItemState.CAPTURING).copy(operationId = operationId, nextActionAt = null)

/** The item's backfill has no budget left for the current step's operation (plan Rule 39): the item fails at once, unscored. */
fun AutomationItemProgress.outOfBudget(message: String?): AutomationItemProgress = lost(AutomationFailures.BACKFILL_BUDGET, message)

/** The current step may not start before [at]. */
fun AutomationItemProgress.waitingUntil(at: Instant): AutomationItemProgress =
    onCurrentStep(AutomationItemState.WAITING).copy(nextActionAt = at)

fun AutomationItemProgress.captured(datasetId: String): AutomationItemProgress = onCurrentStep(AutomationItemState.CAPTURED)
    .copy(datasetId = datasetId, operationId = null, failureCode = null, failureMessage = null, nextActionAt = null)

/** Every entry of the sweep plan has its row; the item has taken its last step, on the sweep attempt that completed it. */
fun AutomationItemProgress.simulated(): AutomationItemProgress = copy(state = AutomationItemState.SIMULATED, step = AutomationStep.SWEEP,
    stepAttempt = sweepAttempt, failureCode = null, failureMessage = null, nextActionAt = null)

/** The capture's verdict leaves the first buy unproven; the item keeps the operation whose verdict showed it. */
fun AutomationItemProgress.skipped(classification: String?): AutomationItemProgress =
    onCurrentStep(AutomationItemState.SKIPPED_NOT_FIRST_BUY).copy(classification = classification, nextActionAt = null)

private fun AutomationItemProgress.onCurrentStep(state: AutomationItemState): AutomationItemProgress =
    copy(state = state, step = currentStep, stepAttempt = currentAttempt)
