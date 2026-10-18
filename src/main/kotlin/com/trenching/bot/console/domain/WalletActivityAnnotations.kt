package com.trenching.bot.console.domain

import com.trenching.bot.walletactivity.WalletActivityPage
import com.trenching.bot.walletactivity.domain.WalletActivitySignals
import java.time.Instant

/** Adds the token-picking readings to every action of a page, keeping the page's order. */
object WalletActivityAnnotations {
    fun annotate(page: WalletActivityPage, captured: Map<String, List<CaptureReference>>, cachedAt: Instant?): WalletActivityView =
        WalletActivityView(page, page.actions.map { action ->
            WalletActionView(
                action = action,
                earlierActionInPage = WalletActivitySignals.hasEarlierActionInPage(action, page.actions),
                overSignalThreshold = WalletActivitySignals.overSignalThreshold(action),
                signalBasis = WalletActivitySignals.SIGNAL_BASIS,
                capturedIn = captured[action.mint.value].orEmpty(),
            )
        }, cachedAt)
}
