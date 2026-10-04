package com.trenching.bot.walletactivity.infrastructure.capture

import com.trenching.bot.walletactivity.WalletHistoryOutcome
import com.trenching.bot.walletactivity.WalletHistoryCaptureLimits

internal object WalletCaptureFormat {
    const val VERSION = 2
    const val MAX_SELECTION = WalletHistoryCaptureLimits.MAX_TRANSACTIONS
    const val MAX_SCAN_PAGES = WalletHistoryCaptureLimits.MAX_SCAN_PAGES
    const val MAX_CAPTURE_BYTES = WalletHistoryCaptureLimits.MAX_BYTES
    const val MAX_REQUESTS = WalletHistoryCaptureLimits.MAX_REQUESTS
    const val MANIFEST = "manifest.json"
    val UNRESOLVED_OUTCOMES = setOf(WalletHistoryOutcome.PENDING, WalletHistoryOutcome.PROVIDER_UNAVAILABLE, WalletHistoryOutcome.INVALID_TRANSACTION)
    val ARTIFACT_OUTCOMES = setOf(WalletHistoryOutcome.FAILED, WalletHistoryOutcome.NON_SIGNER, WalletHistoryOutcome.UNCLASSIFIED)
}
