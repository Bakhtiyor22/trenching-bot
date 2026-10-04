package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.CaptureLimits

object WalletHistoryCaptureLimits {
    const val MAX_TRANSACTIONS = 100
    const val DEFAULT_TRANSACTIONS = 100
    const val MAX_SCAN_PAGES = CaptureLimits.MAX_SCAN_PAGES
    const val DEFAULT_SCAN_PAGES = CaptureLimits.DEFAULT_SCAN_PAGES
    const val MAX_REQUESTS = CaptureLimits.MAX_REQUESTS
    const val DEFAULT_REQUESTS = CaptureLimits.DEFAULT_REQUESTS
    const val MAX_BYTES = CaptureLimits.MAX_BYTES
    const val DEFAULT_BYTES = CaptureLimits.DEFAULT_BYTES
}
