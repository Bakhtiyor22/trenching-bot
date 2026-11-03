package com.trenching.bot.console.automation.domain

import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.WalletActivityUnavailable

/**
 * One discovery scan's actions down to [bound], oldest first; [newest] is the newest of them. A scan the page cap cut short sets
 * [resumeBefore], the last `nextBefore` it read, where the next scan continues; a [complete] scan reached its bound.
 */
data class DiscoveryBatch(
    val wallet: String,
    val actions: List<WalletAction>,
    val newest: DiscoveryCursor?,
    val bound: DiscoveryBound,
    val resumeBefore: String?,
) {
    val complete: Boolean get() = resumeBefore == null
}

/** What one discovery scan stored: new items, buys counted below the filter, sessions opened, and whether the watermark moved. */
data class DiscoveryRecord(val itemsCreated: Int, val belowFilter: Int, val sessionsOpened: Int, val watermarkAdvanced: Boolean)

sealed interface DiscoveryOutcome {
    data object OutsideWindow : DiscoveryOutcome

    /** The provider could not be read; nothing was stored and the next discovery tick reads the same range. */
    data class Unavailable(val reason: WalletActivityUnavailable) : DiscoveryOutcome

    data class Recorded(val record: DiscoveryRecord) : DiscoveryOutcome
}
