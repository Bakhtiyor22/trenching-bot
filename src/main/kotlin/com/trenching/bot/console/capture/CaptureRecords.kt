package com.trenching.bot.console.capture

import com.fasterxml.jackson.annotation.JsonInclude
import com.trenching.bot.console.api.dto.*
import com.trenching.bot.console.domain.ConsoleException
import java.time.Instant
import tools.jackson.databind.JsonNode
import com.trenching.bot.replay.CaptureCaseInspection

/**
 * [qualification] is the wallet + token verdict from the latest inspection; the successful [qualifyOperationId] of the current export replaces it.
 * [nameKey] and [renameRequests] (request id to the name it set) are left out of the JSON when absent, so files written before them stay unchanged.
 */
data class CaptureSessionRecord(val version: Int = 1, val id: String, val requestId: String, val fingerprint: String, val name: String, val createdAt: Instant, val wallet: CaptureWalletSettings?, val walletAttemptId: String, val marketAttemptId: String?, val ratesAttemptId: String?, val inspection: CaptureInspectionResponse? = null, val operationId: String? = null, val exportOperationId: String? = null, val calibrationOperationId: String? = null, val datasetId: String? = null, val qualification: CaptureQualificationResponse? = null, val qualifyOperationId: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val nameKey: String? = null, @get:JsonInclude(JsonInclude.Include.NON_EMPTY) val renameRequests: Map<String, String> = emptyMap())
data class CaptureAttemptRecord(val id: String, val requestId: String, val fingerprint: String, val stage: String, val createdAt: Instant, val directory: String, val attached: Boolean, val market: CaptureMarketSettings?, val walletAttemptId: String, val marketAttemptId: String?)
data class CaptureRecoveryRecord(val totalRequestBudget: Int, val totalByteBudget: Long, val maxAttempts: Int, val attemptsUsed: Int = 0, val requestsUsed: Int = 0, val bytesUsed: Long = 0, val reservedRequests: Int = 0, val reservedBytes: Long = 0, val nextRetryAt: Instant? = null, val control: String? = null, val retryRequestIds: List<String> = emptyList(), val controlRequestIds: List<String> = emptyList())
data class CaptureOperationRecord(val id: String, val requestId: String, val fingerprint: String, val step: String, val attemptId: String?, val state: String, val startedAt: Instant, val updatedAt: Instant, val endedAt: Instant? = null, val budget: CaptureBudget, val scanPages: Int?, val progress: CaptureProgressResponse? = null, val code: String? = null, val message: String? = null, val walletAttemptId: String, val marketAttemptId: String?, val ratesAttemptId: String?, val inspection: CaptureInspectionResponse? = null, val walletSettings: CaptureWalletSettings? = null, val artifact: String? = null, val checksum: String? = null, val calibration: JsonNode? = null, val qualification: CaptureQualificationResponse? = null, val recovery: CaptureRecoveryRecord? = null, val caseId: String? = null, val control: String? = null, val controlRequestIds: List<String> = emptyList(), val caseInspection: CaptureCaseInspection? = null, val caseExport: CaptureCaseExportRecord? = null, val cutoffMode: String? = null)
data class CaptureCaseRecord(val version: Int = 1, val caseId: String, val walletAttemptId: String, val marketAttemptId: String, val ratesAttemptId: String?, val operationId: String? = null, val ratesOperationId: String? = null, val exportOperationId: String? = null, val datasetId: String? = null)
data class CaptureCaseExportRecord(val caseId: String, val wallet: String, val mint: String, val anchorSignature: String, val anchorSlot: Long, val instructionIndex: Int, val fromSlot: Long, val toSlot: Long, val windowParameters: Map<String, Long>, val extractor: String, val artifact: String, val checksum: String, val provenanceArtifact: String)
data class CaptureCaseView(val caseId: String, val wallet: String, val mint: String, val anchorSignature: String, val anchorSlot: Long, val instructionIndex: Int, val fromSlot: Long, val toSlot: Long, val windowParameters: Map<String, Long>, val selected: Int, val captured: Int, val gaps: Int, val marketStage: String, val coverage: String, val progress: CaptureProgressResponse?, val readiness: String, val captureComplete: Boolean, val validated: Boolean, val ready: Boolean, val exportReady: Boolean, val canRegisterDataset: Boolean, val canCaptureRates: Boolean, val canExport: Boolean, val blocker: String?, val operationId: String?, val operationState: String?, val datasetId: String?, val observedThrough: Long?)
data class CaptureCasesView(val items: List<CaptureCaseView>)
data class CaptureSessionView(val id: String, val name: String, val displayName: String, val createdAt: Instant, val wallet: CaptureWalletSettings?, val walletAttemptId: String, val marketAttempt: CaptureAttemptRecord?, val ratesAttempt: CaptureAttemptRecord?, val inspection: CaptureInspectionResponse?, val operation: CaptureOperationRecord?, val datasetReady: Boolean, val calibrationReady: Boolean, val datasetId: String?, val calibration: JsonNode?, val qualification: CaptureQualificationResponse?, val summary: CaptureSummary)
data class CapturePage<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)
/** [total] counts the sessions the status filter keeps; [statusCounts] counts every session by summary status. */
data class CaptureSessionPage(val items: List<CaptureSessionView>, val page: Int, val size: Int, val total: Long, val statusCounts: Map<CaptureSummaryStatus, Long>)
/** [suggestion] is a free name offered with a `NAME_TAKEN` conflict. */
class CaptureRequestException(val status: Int, val code: String, override val message: String, val fields: Map<String, String> = emptyMap(), val suggestion: String? = null) : ConsoleException(message)
fun interface CaptureIds { fun next(): String }
object CaptureNames {
    const val WALLET = "WALLET"
    const val MARKET = "MARKET"
    const val RATES = "RATES"
    const val INSPECT = "INSPECT"
    const val EXPORT = "EXPORT"
    const val CALIBRATE = "CALIBRATE"
    const val QUALIFY = "QUALIFY"
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val INCOMPLETE = "INCOMPLETE"
    const val FAILED = "FAILED"
    const val INTERRUPTED = "INTERRUPTED"
    const val WAITING_RETRY = "WAITING_RETRY"
    const val PAUSED = "PAUSED"
    const val CANCELLED = "CANCELLED"
    const val PAUSE_REQUESTED = "PAUSE_REQUESTED"
    const val CANCEL_REQUESTED = "CANCEL_REQUESTED"
    const val BLOCKED = "BLOCKED"
    const val EXPORT_CASE = "EXPORT_CASE"
    const val CASE_RATES = "CASE_RATES"
}
