package com.trenching.bot.marketdata

import com.trenching.bot.chaincapture.CaptureLimits

object MarketHistoryCaptureLimits {
    const val MAX_WINDOWS = 100

    /** A 30-minute window of a busy mint can hold thousands of trades; see the dip-entry-strategy plan for the cost. */
    const val MAX_TRANSACTIONS = 50_000
    const val DEFAULT_TRANSACTIONS = 300

    /** The longest window a capture records, matching the strategy's longest horizon. */
    const val MAX_HORIZON_SLOTS = 4500L
    /** Above the wallet bound: a busy mint can list far more signatures after a window than a wallet does. */
    const val MAX_SCAN_PAGES = 500
    const val DEFAULT_SCAN_PAGES = CaptureLimits.DEFAULT_SCAN_PAGES
    const val MAX_REQUESTS = 60_000
    const val DEFAULT_REQUESTS = CaptureLimits.DEFAULT_REQUESTS
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024
    const val DEFAULT_BYTES = CaptureLimits.DEFAULT_BYTES
}
