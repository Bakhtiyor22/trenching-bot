package com.trenching.bot.console.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import tools.jackson.databind.JsonNode

/** [mint] is the target mint of the `wallet-token` scope; it is left out of the JSON when absent, so earlier fingerprints stay valid. */
data class CaptureWalletSettings(val wallet: String, val scope: String = "pumpfun-user-buys", val limit: Int = 1, val scanPages: Int = 20, @get:JsonInclude(JsonInclude.Include.NON_NULL) val mint: String? = null)
data class CaptureMarketSettings(val scanPages: Int = 50, val maxTransactions: Int = 50000)
data class CaptureBudget(val requestBudget: Int = 400, val byteBudget: Long = 104857600, val operationTimeoutMinutes: Int = 10)
data class CaptureAttachment(val wallet: String, val market: String? = null, val rates: String? = null)
data class CaptureCreateRequest(val requestId: String, val name: String, val wallet: CaptureWalletSettings? = null, val attach: CaptureAttachment? = null)
data class CaptureAttemptRequest(val requestId: String, val stage: String, val market: CaptureMarketSettings? = null)
data class CaptureOperationRequest(val requestId: String, val step: String, val attemptId: String? = null, val budget: CaptureBudget = CaptureBudget(), val scanPages: Int? = null)
data class CaptureStageResponse(val state: String, val reason: String?, val canStart: Boolean, val canResume: Boolean, val requiresNewAttempt: Boolean)
data class CaptureProgressResponse(val phase: String, val at: Instant, val operationRequests: Int?, val operationBytes: Long?, val cumulativeRequests: Int?, val cumulativeBytes: Long?, val processed: Int?, val selected: Int?)
data class CaptureAttemptResponse(val id: String, val stage: String, val createdAt: Instant, val attached: Boolean, val market: CaptureMarketSettings?, val walletAttemptId: String, val marketAttemptId: String?)
data class CaptureInspectionResponse(val wallet: CaptureStageResponse, val market: CaptureStageResponse, val rates: CaptureStageResponse, val canCalibrate: Boolean, val canExport: Boolean, val walletSummary: Map<String, String>, val marketSummary: Map<String, String>, val ratesSummary: Map<String, String>, val marketWindows: List<CaptureWindowResponse> = emptyList(), val targetToken: CaptureTargetTokenResponse? = null)
data class CaptureOperationResponse(val id: String, val requestId: String, val step: String, val attemptId: String?, val state: String, val startedAt: Instant, val updatedAt: Instant, val endedAt: Instant?, val budget: CaptureBudget, val scanPages: Int?, val progress: CaptureProgressResponse?, val code: String?, val message: String?)
data class CaptureSessionResponse(val id: String, val name: String, val createdAt: Instant, val wallet: CaptureWalletSettings?, val walletAttemptId: String, val marketAttempt: CaptureAttemptResponse?, val ratesAttempt: CaptureAttemptResponse?, val inspection: CaptureInspectionResponse?, val operation: CaptureOperationResponse?, val datasetReady: Boolean, val calibrationReady: Boolean, val datasetId: String?, val calibration: JsonNode?, val qualification: CaptureQualificationResponse?)
data class CaptureSessionPageResponse(val items: List<CaptureSessionResponse>, val page: Int, val size: Int, val total: Long)
data class CaptureAttemptPageResponse(val items: List<CaptureAttemptResponse>, val page: Int, val size: Int, val total: Long)
data class CaptureConfigResponse(val rpcConfigured: Boolean, val ratesConfigured: Boolean, val walletDefaults: CaptureWalletSettings, val walletTokenDefaults: CaptureWalletSettings, val walletTokenBudgetDefaults: CaptureBudget, val marketDefaults: CaptureMarketSettings, val budgetDefaults: CaptureBudget, val bounds: Map<String, Long>, val windowSlots: Long)

data class CaptureWindowResponse(val mint: String, val fromSlot: Long, val toSlot: Long, val coverage: String, val scanned: Int, val selected: Int, val detailGaps: Int?, val observations: Int?)

