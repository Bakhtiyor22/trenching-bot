package com.trenching.bot.chaincapture

/** Listing-scan and acquisition-budget bounds shared by every capture command. */
object CaptureLimits {
    const val MAX_SCAN_PAGES = 50
    const val DEFAULT_SCAN_PAGES = 20
    const val MAX_REQUESTS = 1000
    const val DEFAULT_REQUESTS = 400
    const val MAX_BYTES = 500L * 1024 * 1024
    const val DEFAULT_BYTES = 100L * 1024 * 1024
}
