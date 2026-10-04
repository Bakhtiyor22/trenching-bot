package com.trenching.bot.walletactivity

import com.trenching.bot.common.WalletAddress
import java.nio.file.Path

interface WalletHistoryCapture {
    fun capture(request: WalletHistoryCaptureRequest): WalletHistoryCaptureResult
}

data class WalletHistoryCaptureRequest(
    val wallet: WalletAddress,
    val directory: Path,
    val limit: Int = WalletHistoryCaptureLimits.DEFAULT_TRANSACTIONS,
    val scanPages: Int = WalletHistoryCaptureLimits.DEFAULT_SCAN_PAGES,
    val requestBudget: Int = WalletHistoryCaptureLimits.DEFAULT_REQUESTS,
    val byteBudget: Long = WalletHistoryCaptureLimits.DEFAULT_BYTES,
)

data class WalletHistoryCaptureResult(
    val directory: Path,
    val manifest: WalletHistoryManifest,
)
