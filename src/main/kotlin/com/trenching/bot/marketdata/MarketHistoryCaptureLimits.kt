package com.trenching.bot.marketdata

import com.trenching.bot.chaincapture.CaptureLimits

object MarketHistoryCaptureLimits {
    const val MAX_WINDOWS = 100
    const val MAX_TRANSACTIONS = 1000
    const val DEFAULT_TRANSACTIONS = 300
    /** Above the wallet bound: a busy mint can list far more signatures after a window than a wallet does. */
    const val MAX_SCAN_PAGES = 500
    const val DEFAULT_SCAN_PAGES = CaptureLimits.DEFAULT_SCAN_PAGES
    const val MAX_REQUESTS = CaptureLimits.MAX_REQUESTS
    const val DEFAULT_REQUESTS = CaptureLimits.DEFAULT_REQUESTS
    const val MAX_BYTES = CaptureLimits.MAX_BYTES
    const val DEFAULT_BYTES = CaptureLimits.DEFAULT_BYTES
}
