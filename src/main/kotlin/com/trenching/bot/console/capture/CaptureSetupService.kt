package com.trenching.bot.console.capture

import com.trenching.bot.console.domain.Recommendation

/** What the new-capture form starts from: defaults, suggested and recent wallets, and setup recommendations. */
class CaptureSetupService(private val store: CaptureFileStore, private val rpcConfigured: Boolean, private val ratesConfigured: Boolean, private val suggestedWallets: CaptureSuggestedWallets) {
    fun config(): CaptureConfigView = CaptureDefaults.config(rpcConfigured, ratesConfigured, suggestedWallets, recentWallets())

    /** Blank parameters are treated as not chosen yet. */
    fun recommendations(wallet: String?, scope: String?, mint: String?): List<Recommendation> = CaptureRecommendations.of(
        wallet.chosen()?.let(CaptureValidation::walletAddress),
        scope.chosen()?.let(CaptureValidation::scope),
        mint.chosen()?.let(CaptureValidation::mintAddress),
        ratesConfigured,
        suggestedWallets,
    )

    /** The newest distinct wallets of saved sessions; an attachment counts once its inspection has named its wallet. */
    private fun recentWallets(): List<CaptureRecentWallet> = synchronized(store) { store.sessionRecords() }
        .mapNotNull { record -> record.wallet?.let { record to it.wallet } }
        .sortedWith(compareByDescending<Pair<CaptureSessionRecord, String>> { it.first.createdAt }.thenBy { it.first.id })
        .distinctBy { it.second }
        .take(RECENT_WALLETS)
        .map { (record, address) -> CaptureRecentWallet(address, suggestedWallets.labelOf(address), record.createdAt) }

    private fun String?.chosen(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val RECENT_WALLETS = 5
    }
}
