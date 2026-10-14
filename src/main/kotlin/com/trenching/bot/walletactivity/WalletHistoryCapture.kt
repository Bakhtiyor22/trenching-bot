package com.trenching.bot.walletactivity

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.WalletAddress
import java.nio.file.Path
import java.time.Duration

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
    val scope: WalletHistoryScope = WalletHistoryScope.WALLET_REFERENCES,
    /** This invocation's deadline, or null for the transport default; not part of the capture's resume identity. */
    val operationTimeout: Duration? = null,
    /** Required for [WalletHistoryScope.WALLET_TOKEN_ACCOUNT] and refused for every other scope. */
    val targetMint: MintAddress? = null,
)

data class WalletHistoryCaptureResult(
    val directory: Path,
    val manifest: WalletHistoryManifest,
    /** Why this invocation stopped early (a capture budget or provider category), or null; never set by inspection. */
    val stopReason: String? = null,
    val stopRetryAfter: Duration? = null,
)
