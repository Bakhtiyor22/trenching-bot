package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.CaptureBudget
import com.trenching.bot.console.api.dto.CaptureInspectionResponse
import com.trenching.bot.console.capture.CaptureNextActionKind.CAPTURE_RATES
import com.trenching.bot.console.capture.CaptureNextActionKind.INSPECT
import com.trenching.bot.console.capture.CaptureNextActionKind.REGISTER_DATASET
import com.trenching.bot.console.capture.CaptureNextActionKind.RESUME_MARKETS
import com.trenching.bot.console.capture.CaptureNextActionKind.RESUME_WALLET
import com.trenching.bot.console.capture.CaptureNextActionKind.RETRY_WALLET
import com.trenching.bot.console.capture.CaptureNextActionKind.START_MARKETS
import com.trenching.bot.console.capture.CaptureNextActionKind.START_RUN
import com.trenching.bot.console.capture.CaptureNextActionKind.START_WALLET
import com.trenching.bot.console.capture.CaptureNextActionKind.WAIT
import com.trenching.bot.console.capture.CaptureStepStage.DATASET
import com.trenching.bot.console.capture.CaptureStepStage.MARKETS
import com.trenching.bot.console.capture.CaptureStepStage.RATES
import com.trenching.bot.console.capture.CaptureStepStage.WALLET
import com.trenching.bot.replay.CaptureStageState
import com.trenching.bot.walletactivity.TokenAccountGap

/**
 * Derives one capture's [CaptureSummary] from what is saved. The latest main operation speaks first unless it succeeded,
 * then a running case, a dataset or a ready case, a blocked case, and finally the inspected stage states.
 */
object CaptureStatusRules {
    private val ACQUISITION_STAGES = listOf(WALLET, MARKETS, RATES)
    private val LIMIT_STOPS = setOf("REQUESTS", "BYTES", "RESPONSE_BYTES")
    private const val DEADLINE_STOP = "DEADLINE"
    private val UNRESUMABLE_STOP = TokenAccountGap.HISTORY_NOT_EXHAUSTED.name
    private const val PERCENT = 100L

    private enum class OperationState {
        RUNNING, SUCCEEDED, INCOMPLETE, FAILED, INTERRUPTED, WAITING_RETRY, PAUSED, CANCELLED, BLOCKED;

        companion object {
            fun of(value: String): OperationState? = entries.firstOrNull { it.name == value }
        }
    }

    private enum class CaseReadiness { DOWNLOADING, BLOCKED, READY, OPEN }

    private data class Verdict(val status: CaptureSummaryStatus, val reasonCode: String?, val stage: CaptureStepStage, val action: CaptureNextAction, val operation: CaptureOperationRecord? = null)

    fun summarize(input: CaptureStatusInput): CaptureSummary {
        val verdict = fromOperation(input) ?: fromActiveCase(input) ?: fromDataset(input) ?: fromBlockedCase(input) ?: fromStages(input)
        return CaptureSummary(verdict.status, verdict.reasonCode, CaptureCurrentStep(verdict.stage.index, verdict.stage), steps(input), progress(verdict.operation),
            verdict.action, counts(input), verdict.operation?.progress?.at)
    }

    private fun fromOperation(input: CaptureStatusInput): Verdict? {
        val operation = input.operation ?: return null
        val own = stageOf(operation.step)
        val stage = own ?: currentStage(input.inspection)
        val reason = operation.code ?: stageReason(input.inspection, stage)
        return when (OperationState.of(operation.state)) {
            OperationState.SUCCEEDED -> null
            OperationState.RUNNING -> Verdict(CaptureSummaryStatus.RUNNING, reason, stage, action(WAIT, stage), operation)
            OperationState.WAITING_RETRY -> Verdict(CaptureSummaryStatus.WAITING_RETRY, reason, stage, action(WAIT, stage), operation)
            OperationState.PAUSED -> Verdict(CaptureSummaryStatus.PAUSED, reason, stage, resume(input, own, operation.budget.requestBudget), operation)
            OperationState.INCOMPLETE -> stopped(input, own, stage, reason, operation)
            OperationState.FAILED, OperationState.INTERRUPTED -> Verdict(CaptureSummaryStatus.NEEDS_ATTENTION, reason, stage, retry(input, own, operation.budget.requestBudget), operation)
            OperationState.CANCELLED -> Verdict(CaptureSummaryStatus.CANCELLED, reason, stage, retry(input, own, operation.budget.requestBudget), operation)
            OperationState.BLOCKED, null -> Verdict(CaptureSummaryStatus.NEEDS_ATTENTION, reason, stage, action(INSPECT, stage), operation)
        }
    }

    private fun stopped(input: CaptureStatusInput, own: CaptureStepStage?, stage: CaptureStepStage, reason: String?, operation: CaptureOperationRecord): Verdict {
        val resume = resume(input, own, operation.budget.requestBudget)
        return when (operation.code) {
            DEADLINE_STOP -> Verdict(CaptureSummaryStatus.STOPPED_DEADLINE, reason, stage, resume, operation)
            in LIMIT_STOPS -> Verdict(CaptureSummaryStatus.STOPPED_LIMIT, reason, stage, resume, operation)
            UNRESUMABLE_STOP -> Verdict(CaptureSummaryStatus.NEEDS_ATTENTION, reason, stage, action(INSPECT, stage), operation)
            else -> Verdict(CaptureSummaryStatus.NEEDS_ATTENTION, reason, stage, resume, operation)
        }
    }

    private fun fromActiveCase(input: CaptureStatusInput): Verdict? {
        val case = input.cases.firstOrNull { readiness(it) == CaseReadiness.DOWNLOADING } ?: return null
        val operation = requireNotNull(case.operation) { "A downloading case has an operation caseId=${case.caseId}" }
        val stage = caseStage(operation)
        return Verdict(CaptureSummaryStatus.RUNNING, operation.code, stage, CaptureNextAction(WAIT, stage, null, case.caseId, null), operation)
    }

    private fun fromDataset(input: CaptureStatusInput): Verdict? {
        val registered = input.cases.firstOrNull { it.datasetId != null }
        if (input.datasetId != null || registered != null) {
            return Verdict(CaptureSummaryStatus.DONE, null, DATASET, CaptureNextAction(START_RUN, DATASET, null, registered?.caseId.takeIf { input.datasetId == null }, null))
        }
        val ready = input.cases.firstOrNull { readiness(it) == CaseReadiness.READY }
        if (ready != null) return Verdict(CaptureSummaryStatus.READY_TO_REPLAY, null, DATASET, CaptureNextAction(REGISTER_DATASET, DATASET, null, ready.caseId, null))
        return if (input.datasetReady) Verdict(CaptureSummaryStatus.READY_TO_REPLAY, null, DATASET, action(REGISTER_DATASET, DATASET)) else null
    }

    private fun fromBlockedCase(input: CaptureStatusInput): Verdict? {
        val case = input.cases.firstOrNull { readiness(it) == CaseReadiness.BLOCKED } ?: return null
        val operation = requireNotNull(case.operation) { "A blocked case has an operation caseId=${case.caseId}" }
        val stage = caseStage(operation)
        val kind = when (stage) {
            RATES -> CAPTURE_RATES
            WALLET, MARKETS, DATASET -> INSPECT
        }
        return Verdict(CaptureSummaryStatus.NEEDS_ATTENTION, operation.code, stage, CaptureNextAction(kind, stage, null, case.caseId, null), operation)
    }

    private fun fromStages(input: CaptureStatusInput): Verdict {
        val inspection = input.inspection ?: return Verdict(CaptureSummaryStatus.NOT_STARTED, null, WALLET, start(input, WALLET))
        return ACQUISITION_STAGES.firstNotNullOfOrNull { stage ->
            val reason = stageReason(inspection, stage)
            when (stageState(inspection, stage)) {
                CaptureStageState.READY -> null
                CaptureStageState.NOT_STARTED -> Verdict(CaptureSummaryStatus.NOT_STARTED, reason, stage, start(input, stage))
                CaptureStageState.INCOMPLETE -> Verdict(CaptureSummaryStatus.PAUSED, reason, stage, resume(input, stage, null))
                CaptureStageState.UNUSABLE, CaptureStageState.INVALID -> Verdict(CaptureSummaryStatus.NEEDS_ATTENTION, reason, stage, action(INSPECT, stage))
            }
        } ?: Verdict(CaptureSummaryStatus.READY_TO_REPLAY, null, DATASET, action(REGISTER_DATASET, DATASET))
    }

    private fun steps(input: CaptureStatusInput): List<CaptureStepSummary> {
        val states = ACQUISITION_STAGES.associateWithTo(LinkedHashMap()) { stage -> input.inspection?.let { stepState(stageState(it, stage)) } ?: CaptureStepState.TODO }
        val registered = input.datasetId != null || input.cases.any { it.datasetId != null }
        states[DATASET] = if (registered) CaptureStepState.DONE else CaptureStepState.TODO
        if (states[RATES] == CaptureStepState.TODO && (registered || input.cases.any { readiness(it) == CaseReadiness.READY })) states[RATES] = CaptureStepState.DONE
        input.cases.firstOrNull { readiness(it) == CaseReadiness.DOWNLOADING }?.operation?.let { states[caseStage(it)] = CaptureStepState.ACTIVE }
        input.operation?.let { operation -> stageOf(operation.step)?.let { stage -> operationStepState(operation)?.let { states[stage] = it } } }
        return states.map { (stage, state) -> CaptureStepSummary(stage, state) }
    }

    private fun counts(input: CaptureStatusInput): CaptureCaseCounts {
        val readiness = input.cases.map(::readiness)
        return CaptureCaseCounts(
            total = maxOf(input.inspection?.marketWindows?.size ?: 0, input.cases.size),
            ready = readiness.count { it == CaseReadiness.READY },
            blocked = readiness.count { it == CaseReadiness.BLOCKED },
            downloading = readiness.count { it == CaseReadiness.DOWNLOADING },
        )
    }

    /** The same readiness the case view reports, from the case's saved operations only. */
    private fun readiness(case: CaptureCaseFact): CaseReadiness = when (case.operation?.let { OperationState.of(it.state) }) {
        OperationState.RUNNING, OperationState.WAITING_RETRY, OperationState.PAUSED -> CaseReadiness.DOWNLOADING
        OperationState.FAILED, OperationState.CANCELLED, OperationState.INCOMPLETE -> CaseReadiness.BLOCKED
        OperationState.SUCCEEDED, OperationState.INTERRUPTED, OperationState.BLOCKED, null ->
            if (case.validated || case.exported) CaseReadiness.READY else CaseReadiness.OPEN
    }

    private fun progress(operation: CaptureOperationRecord?): CaptureSummaryProgress? {
        val progress = operation?.progress ?: return null
        val processed = progress.processed ?: return null
        val selected = progress.selected?.takeIf { it > 0 } ?: return null
        return CaptureSummaryProgress(processed, selected, Math.floorDiv(processed.toLong() * PERCENT, selected.toLong()).toString())
    }

    private fun start(input: CaptureStatusInput, stage: CaptureStepStage): CaptureNextAction = when (stage) {
        WALLET -> CaptureNextAction(START_WALLET, WALLET, input.walletAttemptId, null, CaptureDefaults.walletBudget(input.walletScope).requestBudget)
        MARKETS -> CaptureNextAction(START_MARKETS, MARKETS, input.marketAttemptId, null, CaptureBudget().requestBudget)
        RATES -> CaptureNextAction(CAPTURE_RATES, RATES, input.ratesAttemptId, null, null)
        DATASET -> action(REGISTER_DATASET, DATASET)
    }

    private fun resume(input: CaptureStatusInput, stage: CaptureStepStage?, requests: Int?) = continuation(input, stage, RESUME_WALLET, requests)

    private fun retry(input: CaptureStatusInput, stage: CaptureStepStage?, requests: Int?) = continuation(input, stage, RETRY_WALLET, requests)

    /** How a step that stopped carries on; markets always resume from their saved checkpoint, and rates recapture only what is missing. */
    private fun continuation(input: CaptureStatusInput, stage: CaptureStepStage?, walletKind: CaptureNextActionKind, requests: Int?): CaptureNextAction = when (stage) {
        WALLET -> CaptureNextAction(walletKind, WALLET, input.walletAttemptId, null, requests ?: CaptureDefaults.walletBudget(input.walletScope).requestBudget)
        MARKETS -> CaptureNextAction(RESUME_MARKETS, MARKETS, input.marketAttemptId, null, requests ?: CaptureBudget().requestBudget)
        RATES -> CaptureNextAction(CAPTURE_RATES, RATES, input.ratesAttemptId, null, null)
        DATASET, null -> action(INSPECT, stage)
    }

    private fun action(kind: CaptureNextActionKind, stage: CaptureStepStage?) = CaptureNextAction(kind, stage, null, null, null)

    private fun stageOf(step: String): CaptureStepStage? = when (step) {
        CaptureNames.WALLET -> WALLET
        CaptureNames.MARKET -> MARKETS
        CaptureNames.RATES -> RATES
        CaptureNames.EXPORT, CaptureNames.CALIBRATE, CaptureNames.QUALIFY -> DATASET
        else -> null
    }

    private fun caseStage(operation: CaptureOperationRecord): CaptureStepStage = if (operation.step == CaptureNames.EXPORT) DATASET else RATES

    private fun currentStage(inspection: CaptureInspectionResponse?): CaptureStepStage {
        val saved = inspection ?: return WALLET
        return ACQUISITION_STAGES.firstOrNull { stageState(saved, it) != CaptureStageState.READY } ?: DATASET
    }

    private fun stageState(inspection: CaptureInspectionResponse, stage: CaptureStepStage): CaptureStageState {
        val value = stageResponse(inspection, stage)?.state ?: return CaptureStageState.NOT_STARTED
        return CaptureStageState.entries.firstOrNull { it.name == value } ?: CaptureStageState.INVALID
    }

    private fun stageReason(inspection: CaptureInspectionResponse?, stage: CaptureStepStage): String? = inspection?.let { stageResponse(it, stage)?.reason }

    private fun stageResponse(inspection: CaptureInspectionResponse, stage: CaptureStepStage) = when (stage) {
        WALLET -> inspection.wallet
        MARKETS -> inspection.market
        RATES -> inspection.rates
        DATASET -> null
    }

    private fun stepState(state: CaptureStageState): CaptureStepState = when (state) {
        CaptureStageState.READY -> CaptureStepState.DONE
        CaptureStageState.NOT_STARTED -> CaptureStepState.TODO
        CaptureStageState.INCOMPLETE -> CaptureStepState.STOPPED
        CaptureStageState.UNUSABLE, CaptureStageState.INVALID -> CaptureStepState.FAILED
    }

    private fun operationStepState(operation: CaptureOperationRecord): CaptureStepState? = when (OperationState.of(operation.state)) {
        OperationState.SUCCEEDED -> null
        OperationState.RUNNING, OperationState.WAITING_RETRY -> CaptureStepState.ACTIVE
        OperationState.PAUSED, OperationState.INCOMPLETE, OperationState.CANCELLED -> CaptureStepState.STOPPED
        OperationState.FAILED, OperationState.INTERRUPTED, OperationState.BLOCKED, null -> CaptureStepState.FAILED
    }
}
