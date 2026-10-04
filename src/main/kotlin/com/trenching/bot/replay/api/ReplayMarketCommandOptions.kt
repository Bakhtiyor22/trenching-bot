package com.trenching.bot.replay.api

import com.trenching.bot.marketdata.MarketHistoryCaptureLimits

internal object ReplayMarketCommandOptions {
    const val WALLET_CAPTURE_DIRECTORY = "--wallet-capture-directory"
    const val DIRECTORY = "--directory"
    const val SCAN_PAGES = "--scan-pages"
    const val MAX_TRANSACTIONS = "--max-transactions"
    const val REQUEST_BUDGET = "--request-budget"
    const val BYTE_BUDGET = "--byte-budget"
    const val DEFAULT_SCAN_PAGES = MarketHistoryCaptureLimits.DEFAULT_SCAN_PAGES
    const val DEFAULT_MAX_TRANSACTIONS = MarketHistoryCaptureLimits.DEFAULT_TRANSACTIONS
    const val DEFAULT_REQUEST_BUDGET = MarketHistoryCaptureLimits.DEFAULT_REQUESTS
    const val DEFAULT_BYTE_BUDGET = MarketHistoryCaptureLimits.DEFAULT_BYTES
    const val OBSERVATION_LIMIT = 3
    const val EXTRACTOR_NOT_RUN = "NOT_RUN"
    const val EXTRACTOR_CAPTURE_INCOMPLETE = "CAPTURE_INCOMPLETE"
    const val CAPTURE_USAGE = "Usage: captureReplayMarkets --wallet-capture-directory <path> --directory <path> [--scan-pages <1..50>] " +
        "[--max-transactions <1..1000>] [--request-budget <1..1000>] [--byte-budget <1..524288000>]"
    const val INSPECT_USAGE = "Usage: inspectReplayMarkets --directory <path>"
    val CAPTURE_OPTIONS = setOf(WALLET_CAPTURE_DIRECTORY, DIRECTORY, SCAN_PAGES, MAX_TRANSACTIONS, REQUEST_BUDGET, BYTE_BUDGET)
}
