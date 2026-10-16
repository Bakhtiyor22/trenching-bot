package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.CaptureInspectionResponse
import java.time.Instant

enum class CaptureSummaryStatus { NOT_STARTED, RUNNING, WAITING_RETRY, PAUSED, STOPPED_LIMIT, STOPPED_DEADLINE, NEEDS_ATTENTION, READY_TO_REPLAY, DONE, CANCELLED }

enum class CaptureStepStage(val index: Int) { WALLET(1), MARKETS(2), RATES(3), DATASET(4) }

enum class CaptureStepState { DONE, ACTIVE, STOPPED, FAILED, TODO }

enum class CaptureNextActionKind { START_WALLET, RESUME_WALLET, RETRY_WALLET, START_MARKETS, RESUME_MARKETS, CAPTURE_RATES, REGISTER_DATASET, START_RUN, VIEW_RESULTS, INSPECT, WAIT, NONE }

data class CaptureCurrentStep(val index: Int, val stage: CaptureStepStage)

data class CaptureStepSummary(val stage: CaptureStepStage, val state: CaptureStepState)

/** [percent] is `processed × 100 / selected` floored at scale 0, so it reads 100 only when every selected item is processed. */
data class CaptureSummaryProgress(val processed: Int, val selected: Int, val percent: String)

/** [maxRequests] is the request allowance the action would spend, when the action takes one. */
data class CaptureNextAction(val kind: CaptureNextActionKind, val stage: CaptureStepStage?, val attemptId: String?, val caseId: String?, val maxRequests: Int?)

data class CaptureCaseCounts(val total: Int, val ready: Int, val blocked: Int, val downloading: Int)

data class CaptureSummary(
    val status: CaptureSummaryStatus,
    val reasonCode: String?,
    val currentStep: CaptureCurrentStep,
    val steps: List<CaptureStepSummary>,
    val progress: CaptureSummaryProgress?,
    val nextAction: CaptureNextAction,
    val cases: CaptureCaseCounts,
    val lastProgressAt: Instant?,
)

/** What the case records on disk say about one case of the current attempts; [operation] is the case's latest operation. */
data class CaptureCaseFact(val caseId: String, val operation: CaptureOperationRecord?, val validated: Boolean, val exported: Boolean, val datasetId: String?)

/** The saved session facts a summary is derived from; [operation] is the session's latest main operation. */
data class CaptureStatusInput(
    val walletAttemptId: String,
    val marketAttemptId: String?,
    val ratesAttemptId: String?,
    val walletScope: CaptureWalletScope?,
    val inspection: CaptureInspectionResponse?,
    val operation: CaptureOperationRecord?,
    val datasetReady: Boolean,
    val datasetId: String?,
    val cases: List<CaptureCaseFact>,
)
