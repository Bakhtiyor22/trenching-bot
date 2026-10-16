package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.CaptureLimits

object WalletHistoryCaptureLimits {
    const val MAX_TRANSACTIONS = 1000
    const val DEFAULT_TRANSACTIONS = 100
    const val MAX_SCAN_PAGES = CaptureLimits.MAX_SCAN_PAGES
    const val DEFAULT_SCAN_PAGES = CaptureLimits.DEFAULT_SCAN_PAGES
    const val MAX_REQUESTS = CaptureLimits.MAX_REQUESTS
    const val DEFAULT_REQUESTS = CaptureLimits.DEFAULT_REQUESTS
    const val MAX_BYTES = CaptureLimits.MAX_BYTES
    const val DEFAULT_BYTES = CaptureLimits.DEFAULT_BYTES

    /**
     * Defaults for [WalletHistoryScope.WALLET_TOKEN_ACCOUNT]: the capture is complete only once its listing reaches the end of
     * the account's history, so the defaults leave room for accounts with many records and failed entries.
     */
    const val TOKEN_ACCOUNT_DEFAULT_TRANSACTIONS = MAX_TRANSACTIONS
    const val TOKEN_ACCOUNT_DEFAULT_SCAN_PAGES = CaptureLimits.DEFAULT_SCAN_PAGES

    /** Caps of the actual-first-buy verification: pages per listing, transaction fetches in total, and creation fetches. */
    const val VERIFICATION_LISTING_PAGES = CaptureLimits.MAX_SCAN_PAGES
    const val VERIFICATION_FETCHES = 500
    const val CREATION_FETCHES = 10
}
