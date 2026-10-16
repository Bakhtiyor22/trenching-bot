package com.trenching.bot.walletactivity

import com.trenching.bot.walletactivity.infrastructure.capture.WalletCaptureFormat

/** How far an incomplete wallet capture got, for modules that act on [WalletHistoryManifest.complete]. */
object WalletHistoryCompletion {
    /**
     * A token-account capture that requested everything it could but stopped listing at its record or page limit before the
     * end of the account's history. Resuming cannot complete it; its records still decode, so its first-buy checks stay readable.
     */
    fun historyNotExhausted(manifest: WalletHistoryManifest): Boolean =
        WalletCaptureFormat.acquisitionFinished(manifest) && !WalletCaptureFormat.isComplete(manifest)
}
