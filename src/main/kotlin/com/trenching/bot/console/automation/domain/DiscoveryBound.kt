package com.trenching.bot.console.automation.domain

import com.trenching.bot.walletactivity.WalletAction
import java.time.Instant

/** Where a newest-first discovery scan stops (plan Rule 9). */
sealed interface DiscoveryBound {
    fun reached(action: WalletAction): Boolean

    /**
     * Whether a page whose oldest listed transaction has [oldestBlockTime] already passed the bound, so older pages hold
     * nothing to discover even when this one decoded no trade (plan Rule 11, amended 2026-11-03).
     */
    fun listingReached(oldestBlockTime: Instant?): Boolean

    /** The wallet's watermark: an action at or below it was processed by an earlier complete scan. */
    data class After(val watermark: DiscoveryCursor) : DiscoveryBound {
        override fun reached(action: WalletAction): Boolean = DiscoveryCursor.of(action) <= watermark

        override fun listingReached(oldestBlockTime: Instant?): Boolean = false
    }

    /** A wallet's first scan: an action before [from] (the clock minus the initial lookback) is not discovered. */
    data class Since(val from: Instant) : DiscoveryBound {
        override fun reached(action: WalletAction): Boolean = action.blockTime?.isBefore(from) ?: false

        override fun listingReached(oldestBlockTime: Instant?): Boolean = oldestBlockTime?.isBefore(from) ?: false
    }
}
