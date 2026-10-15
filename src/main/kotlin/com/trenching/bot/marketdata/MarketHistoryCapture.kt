package com.trenching.bot.marketdata

import com.trenching.bot.marketdata.infrastructure.capture.MarketCaptureFormat
import java.nio.file.Path
import java.time.Duration

interface MarketHistoryCapture {
    fun capture(request: MarketHistoryCaptureRequest): MarketHistoryCaptureResult
}

data class MarketHistoryCaptureRequest(
    val directory: Path,
    val windows: List<MarketCaptureWindow>,
    val windowParameters: MarketCaptureWindowParameters,
    val scanPages: Int = MarketHistoryCaptureLimits.DEFAULT_SCAN_PAGES,
    val maxTransactions: Int = MarketHistoryCaptureLimits.DEFAULT_TRANSACTIONS,
    val requestBudget: Int = MarketHistoryCaptureLimits.DEFAULT_REQUESTS,
    val byteBudget: Long = MarketHistoryCaptureLimits.DEFAULT_BYTES,
    /** This invocation's deadline, or null for the transport default; not part of the capture's resume identity. */
    val operationTimeout: Duration? = null,
    /** Not part of the resume identity; [MarketCutoffMode.DECIDED] requires [cutoff] and [cutoffIdentity]. */
    val cutoffMode: MarketCutoffMode = MarketCutoffMode.FULL,
    val cutoff: MarketCaptureCutoff? = null,
    val cutoffIdentity: CutoffIdentity? = null,
)

data class MarketHistoryCaptureResult(
    val directory: Path,
    val manifest: MarketHistoryManifest,
    /** Why this invocation stopped early (a capture budget or provider category), or null; never set by inspection. */
    val stopReason: String? = null,
    /** Provider Retry-After supplied with [stopReason], preserved for bounded capture recovery. */
    val stopRetryAfter: Duration? = null,
) {
    val manifestFile: Path get() = directory.resolve(MarketCaptureFormat.MANIFEST)
}
