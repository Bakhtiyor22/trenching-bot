package com.trenching.bot.walletactivity

import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import java.time.Duration

object WalletActivityLimits {
    const val MIN_LIMIT = 1
    const val MAX_LIMIT = 50
    const val DEFAULT_LIMIT = 20

    /** Listing entries read per page: three per wanted transaction, so failed entries rarely shorten a page, and at least 100. */
    const val LISTING_ENTRIES_PER_TRANSACTION = 3
    const val MIN_LISTING_ENTRIES = 100

    val OPERATION_TIMEOUT: Duration = Duration.ofSeconds(30)

    const val SOURCE = "pumpfun-user-volume-accumulator"
    const val VENUE = "PUMPFUN_CURVE"
    const val TOKEN_DECIMALS = PumpFunLayout.TOKEN_DECIMALS
}
