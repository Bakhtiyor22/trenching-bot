package com.trenching.bot.console.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import tools.jackson.databind.JsonNode

/** [mint] is the target mint of the `wallet-token` scope; it is left out of the JSON when absent, so earlier fingerprints stay valid. */
data class CaptureWalletSettings(val wallet: String, val scope: String = "pumpfun-user-buys", val limit: Int = 1, val scanPages: Int = 20, @get:JsonInclude(JsonInclude.Include.NON_NULL) val mint: String? = null)
/** [cutoffMode] is `FULL` or `DECIDED`; an attempt saved before it existed has none and captures in full. It is left out of the JSON when absent, so earlier fingerprints stay valid. */
data class CaptureMarketSettings(val scanPages: Int = 50, val maxTransactions: Int = 50000, @get:JsonInclude(JsonInclude.Include.NON_NULL) val cutoffMode: String? = null)
data class CaptureBudget(val requestBudget: Int = 400, val byteBudget: Long = 104857600, val operationTimeoutMinutes: Int = 10)
data class CaptureAttachment(val wallet: String, val market: String? = null, val rates: String? = null)
/** [cutoffMode] sets the new market attempt's mode; absent, the wallet scope decides it. */
data class CaptureCreateRequest(val requestId: String, val name: String, val wallet: CaptureWalletSettings? = null, val attach: CaptureAttachment? = null, @get:JsonInclude(JsonInclude.Include.NON_NULL) val cutoffMode: String? = null)
data class CaptureAttemptRequest(val requestId: String, val stage: String, val market: CaptureMarketSettings? = null)
data class CaptureRecoveryConfig(val totalRequestBudget: Int, val totalByteBudget: Long, val maxAttempts: Int = 3)
/** [cutoffMode] overrides the market attempt's for one MARKET operation; `FULL` on a decided window captures the rest of it. */
data class CaptureOperationRequest(val requestId: String, val step: String, val attemptId: String? = null, val budget: CaptureBudget = CaptureBudget(), val scanPages: Int? = null, @get:JsonInclude(JsonInclude.Include.NON_NULL) val recovery: CaptureRecoveryConfig? = null, @get:JsonInclude(JsonInclude.Include.NON_NULL) val caseId: String? = null, @get:JsonInclude(JsonInclude.Include.NON_NULL) val cutoffMode: String? = null)
data class CaptureOperationControlRequest(val requestId: String)
data class CaptureCaseCommandRequest(val requestId: String)
data class CaptureStageResponse(val state: String, val reason: String?, val canStart: Boolean, val canResume: Boolean, val requiresNewAttempt: Boolean)
data class CaptureProgressResponse(val phase: String, val at: Instant, val operationRequests: Int?, val operationBytes: Long?, val cumulativeRequests: Int?, val cumulativeBytes: Long?, val processed: Int?, val selected: Int?)
data class CaptureAttemptResponse(val id: String, val stage: String, val createdAt: Instant, val attached: Boolean, val market: CaptureMarketSettings?, val walletAttemptId: String, val marketAttemptId: String?)
data class CaptureInspectionResponse(val wallet: CaptureStageResponse, val market: CaptureStageResponse, val rates: CaptureStageResponse, val canCalibrate: Boolean, val canExport: Boolean, val walletSummary: Map<String, String>, val marketSummary: Map<String, String>, val ratesSummary: Map<String, String>, val marketWindows: List<CaptureWindowResponse> = emptyList(), val targetToken: CaptureTargetTokenResponse? = null)
data class CaptureRecoveryResponse(val enabled: Boolean, val totalRequestBudget: Int, val totalByteBudget: Long, val maxAttempts: Int, val attemptsUsed: Int, val requestsUsed: Int, val bytesUsed: Long, val reservedRequests: Int, val reservedBytes: Long, val nextRetryAt: Instant?)
data class CaptureOperationResponse(val id: String, val requestId: String, val step: String, val attemptId: String?, val state: String, val startedAt: Instant, val updatedAt: Instant, val endedAt: Instant?, val budget: CaptureBudget, val scanPages: Int?, val progress: CaptureProgressResponse?, val code: String?, val message: String?, @get:JsonInclude(JsonInclude.Include.NON_NULL) val recovery: CaptureRecoveryResponse? = null, @get:JsonInclude(JsonInclude.Include.NON_NULL) val caseId: String? = null, @get:JsonInclude(JsonInclude.Include.NON_NULL) val cutoffMode: String? = null)
data class CaptureCaseProgressResponse(val phase: String, val at: Instant, val operationRequests: Int?, val operationBytes: Long?, val processed: Int?, val selected: Int?)
data class CaptureCaseResponse(val caseId: String, val wallet: String, val mint: String, val anchorSignature: String, val anchorSlot: Long, val instructionIndex: Int, val fromSlot: Long, val toSlot: Long, val windowParameters: Map<String, Long>, val selected: Int, val captured: Int, val gaps: Int, val marketStage: String, val coverage: String, val progress: CaptureCaseProgressResponse?, val readiness: String, val captureComplete: Boolean, val validated: Boolean, val ready: Boolean, val exportReady: Boolean, val canRegisterDataset: Boolean, val canCaptureRates: Boolean, val canExport: Boolean, val blocker: String?, val operationId: String?, val operationState: String?, val datasetId: String?, val observedThrough: Long? = null)
data class CaptureCasesResponse(val items: List<CaptureCaseResponse>)
data class CaptureSessionResponse(val id: String, val name: String, val createdAt: Instant, val wallet: CaptureWalletSettings?, val walletAttemptId: String, val marketAttempt: CaptureAttemptResponse?, val ratesAttempt: CaptureAttemptResponse?, val inspection: CaptureInspectionResponse?, val operation: CaptureOperationResponse?, val datasetReady: Boolean, val calibrationReady: Boolean, val datasetId: String?, val calibration: JsonNode?, val qualification: CaptureQualificationResponse?)
data class CaptureSessionPageResponse(val items: List<CaptureSessionResponse>, val page: Int, val size: Int, val total: Long)
data class CaptureAttemptPageResponse(val items: List<CaptureAttemptResponse>, val page: Int, val size: Int, val total: Long)
data class CaptureConfigResponse(val rpcConfigured: Boolean, val ratesConfigured: Boolean, val walletDefaults: CaptureWalletSettings, val walletTokenDefaults: CaptureWalletSettings, val walletTokenBudgetDefaults: CaptureBudget, val marketDefaults: CaptureMarketSettings, val budgetDefaults: CaptureBudget, val bounds: Map<String, Long>, val windowSlots: Long)

data class CaptureWindowResponse(val mint: String, val fromSlot: Long, val toSlot: Long, val coverage: String, val scanned: Int, val selected: Int, val detailGaps: Int?, val observations: Int?)

