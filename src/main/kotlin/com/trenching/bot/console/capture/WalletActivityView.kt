package com.trenching.bot.console.capture

import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.WalletActivityPage
import java.time.Instant

/** [cachedAt] is when [page] was fetched, set only when it was served from the 60-second cache. */
data class WalletActivityView(val page: WalletActivityPage, val actions: List<WalletActionView>, val cachedAt: Instant?)

/**
 * [earlierActionInPage] is true when the same mint has an older action in the same page; it is never first-buy proof.
 * [overSignalThreshold] reads the curve payment, excluding fees, as [signalBasis] says.
 */
data class WalletActionView(
    val action: WalletAction,
    val earlierActionInPage: Boolean,
    val overSignalThreshold: Boolean,
    val signalBasis: String,
    val capturedIn: List<CapturedInView>,
)

data class CapturedInView(val captureId: String, val displayName: String)

data class CachedWalletActivity(val page: WalletActivityPage, val fetchedAt: Instant)
