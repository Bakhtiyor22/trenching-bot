package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.*
import com.trenching.bot.console.domain.ConsoleException
import java.time.Instant
import tools.jackson.databind.JsonNode

/** [qualification] is the wallet + token verdict from the latest inspection; the successful [qualifyOperationId] of the current export replaces it. */
data class CaptureSessionRecord(val version: Int = 1, val id: String, val requestId: String, val fingerprint: String, val name: String, val createdAt: Instant, val wallet: CaptureWalletSettings?, val walletAttemptId: String, val marketAttemptId: String?, val ratesAttemptId: String?, val inspection: CaptureInspectionResponse? = null, val operationId: String? = null, val exportOperationId: String? = null, val calibrationOperationId: String? = null, val datasetId: String? = null, val qualification: CaptureQualificationResponse? = null, val qualifyOperationId: String? = null)
data class CaptureAttemptRecord(val id: String, val requestId: String, val fingerprint: String, val stage: String, val createdAt: Instant, val directory: String, val attached: Boolean, val market: CaptureMarketSettings?, val walletAttemptId: String, val marketAttemptId: String?)
data class CaptureOperationRecord(val id: String, val requestId: String, val fingerprint: String, val step: String, val attemptId: String?, val state: String, val startedAt: Instant, val updatedAt: Instant, val endedAt: Instant? = null, val budget: CaptureBudget, val scanPages: Int?, val progress: CaptureProgressResponse? = null, val code: String? = null, val message: String? = null, val walletAttemptId: String, val marketAttemptId: String?, val ratesAttemptId: String?, val inspection: CaptureInspectionResponse? = null, val walletSettings: CaptureWalletSettings? = null, val artifact: String? = null, val checksum: String? = null, val calibration: JsonNode? = null, val qualification: CaptureQualificationResponse? = null)
data class CaptureSessionView(val id: String, val name: String, val createdAt: Instant, val wallet: CaptureWalletSettings?, val walletAttemptId: String, val marketAttempt: CaptureAttemptRecord?, val ratesAttempt: CaptureAttemptRecord?, val inspection: CaptureInspectionResponse?, val operation: CaptureOperationRecord?, val datasetReady: Boolean, val calibrationReady: Boolean, val datasetId: String?, val calibration: JsonNode?, val qualification: CaptureQualificationResponse?)
data class CapturePage<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)
class CaptureRequestException(val status: Int, val code: String, override val message: String, val fields: Map<String, String> = emptyMap()) : ConsoleException(message)
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
}
