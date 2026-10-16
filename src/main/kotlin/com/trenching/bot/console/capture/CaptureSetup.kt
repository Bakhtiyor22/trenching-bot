package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.CaptureBudget
import com.trenching.bot.console.api.dto.CaptureMarketSettings
import com.trenching.bot.console.api.dto.CaptureWalletSettings
import java.time.Instant

/** A configured wallet the capture form offers first; every one is a wallet the agreed strategy copies. */
data class CaptureWalletSuggestion(val label: String, val address: String, val reasonCode: String = AGREED_STRATEGY_WALLET) {
    companion object {
        const val AGREED_STRATEGY_WALLET = "AGREED_STRATEGY_WALLET"
    }
}

data class CaptureSuggestedWallets(val items: List<CaptureWalletSuggestion> = emptyList()) {
    fun labelOf(address: String): String? = items.firstOrNull { it.address == address }?.label
}

/** A wallet an earlier session captured; [lastUsedAt] is the newest such session's creation time. */
data class CaptureRecentWallet(val address: String, val label: String?, val lastUsedAt: Instant)

data class CaptureConfigView(
    val rpcConfigured: Boolean,
    val ratesConfigured: Boolean,
    val walletDefaults: CaptureWalletSettings,
    val walletTokenDefaults: CaptureWalletSettings,
    val walletTokenBudgetDefaults: CaptureBudget,
    val marketDefaults: CaptureMarketSettings,
    val budgetDefaults: CaptureBudget,
    val bounds: Map<String, Long>,
    val windowSlots: Long,
    val suggestedWallets: List<CaptureWalletSuggestion>,
    val recentWallets: List<CaptureRecentWallet>,
)

/** [normalized] is the name as it would be stored; [suggestion] is the first free numbered name when it is taken. */
data class CaptureNameCheck(val available: Boolean, val normalized: String, val suggestion: String?)
