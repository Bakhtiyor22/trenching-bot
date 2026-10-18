package com.trenching.bot.console.domain

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.WalletActivityPage
import java.time.Instant

/** A wallet activity page with its annotated [actions]; [cachedAt] is when it was read, set only when served from the cache. */
data class WalletActivityView(val page: WalletActivityPage, val actions: List<WalletActionView>, val cachedAt: Instant?)

/** [capturedIn] lists the wallet's captures that already hold the action's mint, newest first. */
data class WalletActionView(
    val action: WalletAction,
    val earlierActionInPage: Boolean,
    val overSignalThreshold: Boolean,
    val signalBasis: String,
    val capturedIn: List<CaptureReference>,
)

data class CaptureReference(val captureId: String, val displayName: String)

/** The captures of a wallet that already hold each mint, keyed by mint address; empty when the capture console is absent. */
fun interface CapturedTokens {
    fun capturesOf(wallet: WalletAddress): Map<String, List<CaptureReference>>

    companion object {
        val NONE = CapturedTokens { emptyMap() }
    }
}
