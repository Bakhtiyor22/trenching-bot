package com.trenching.bot.replay.api

import com.trenching.bot.walletactivity.WalletHistoryCaptureLimits
import com.trenching.bot.walletactivity.WalletHistoryScope

internal object WalletHistoryCommandOptions {
    const val WALLET = "--wallet"
    const val DIRECTORY = "--directory"
    const val LIMIT = "--limit"
    const val SCAN_PAGES = "--scan-pages"
    const val REQUEST_BUDGET = "--request-budget"
    const val BYTE_BUDGET = "--byte-budget"
    const val SCOPE = "--scope"
    const val SCOPE_WALLET_REFERENCES = "wallet-references"
    const val SCOPE_PUMPFUN_USER_BUYS = "pumpfun-user-buys"
    val SCOPES = mapOf(
        SCOPE_WALLET_REFERENCES to WalletHistoryScope.WALLET_REFERENCES,
        SCOPE_PUMPFUN_USER_BUYS to WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR,
    )
    const val DEFAULT_LIMIT = WalletHistoryCaptureLimits.DEFAULT_TRANSACTIONS
    const val DEFAULT_SCAN_PAGES = WalletHistoryCaptureLimits.DEFAULT_SCAN_PAGES
    const val DEFAULT_REQUEST_BUDGET = WalletHistoryCaptureLimits.DEFAULT_REQUESTS
    const val DEFAULT_BYTE_BUDGET = WalletHistoryCaptureLimits.DEFAULT_BYTES
    const val CANDIDATE_LIMIT = 3
    const val DECODED_TRADE_LIMIT = 3
    const val DECODER_NOT_RUN = "NOT_RUN"
    const val DECODER_CAPTURE_INCOMPLETE = "CAPTURE_INCOMPLETE"
    const val NO_DISCOVERY_STOP = "NONE"
    const val CAPTURE_USAGE = "Usage: captureWalletHistory --wallet <address> --directory <path> [--limit <1..100>] [--scan-pages <1..50>] [--request-budget <positive integer>] [--byte-budget <positive bytes>] [--scope wallet-references|pumpfun-user-buys] " +
        CaptureCommandArguments.OPERATION_TIMEOUT_USAGE
    const val INSPECT_USAGE = "Usage: inspectWalletHistory --directory <path>"
    val CAPTURE_OPTIONS = setOf(WALLET, DIRECTORY, LIMIT, SCAN_PAGES, REQUEST_BUDGET, BYTE_BUDGET, SCOPE,
        CaptureCommandArguments.OPERATION_TIMEOUT_MINUTES)
}
