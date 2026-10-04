package com.trenching.bot.marketdata

import com.trenching.bot.marketdata.infrastructure.capture.MarketCaptureFormat
import java.nio.file.Path

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
)

data class MarketHistoryCaptureResult(
    val directory: Path,
    val manifest: MarketHistoryManifest,
) {
    val manifestFile: Path get() = directory.resolve(MarketCaptureFormat.MANIFEST)
}
