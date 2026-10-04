package com.trenching.bot.marketdata.infrastructure.capture

import com.trenching.bot.marketdata.MarketHistoryCaptureLimits
import com.trenching.bot.marketdata.MarketTransactionOutcome

internal object MarketCaptureFormat {
    const val VERSION = 1
    const val MANIFEST = "market-manifest.json"
    const val MAX_WINDOWS = MarketHistoryCaptureLimits.MAX_WINDOWS
    const val MAX_TRANSACTIONS = MarketHistoryCaptureLimits.MAX_TRANSACTIONS
    const val MAX_SCAN_PAGES = MarketHistoryCaptureLimits.MAX_SCAN_PAGES
    const val MAX_REQUESTS = MarketHistoryCaptureLimits.MAX_REQUESTS
    const val MAX_CAPTURE_BYTES = MarketHistoryCaptureLimits.MAX_BYTES
    val UNRESOLVED_OUTCOMES = setOf(MarketTransactionOutcome.PENDING, MarketTransactionOutcome.PROVIDER_UNAVAILABLE, MarketTransactionOutcome.INVALID_TRANSACTION)
}
