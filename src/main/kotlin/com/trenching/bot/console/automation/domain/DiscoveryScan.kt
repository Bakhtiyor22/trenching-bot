package com.trenching.bot.console.automation.domain

import com.trenching.bot.walletactivity.WalletActivityUnavailable

/** A discovery scan of the provider: the batch it read, or why the provider could not be read. */
sealed interface DiscoveryScan {
    data class Read(val batch: DiscoveryBatch) : DiscoveryScan

    data class Unavailable(val reason: WalletActivityUnavailable) : DiscoveryScan
}
