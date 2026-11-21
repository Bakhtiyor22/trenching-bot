package com.trenching.bot.console.automation.domain

import com.trenching.bot.walletactivity.WalletActivityUnavailable

/** A discovery scan of the provider: the batch it read, with the pages and provider requests that took, or why the provider could not be read. */
sealed interface DiscoveryScan {
    data class Read(val batch: DiscoveryBatch, val pages: Int, val requests: Int) : DiscoveryScan

    data class Unavailable(val reason: WalletActivityUnavailable) : DiscoveryScan
}
