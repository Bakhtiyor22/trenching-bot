package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.CaptureBudget
import com.trenching.bot.console.api.dto.CaptureMarketSettings
import com.trenching.bot.console.api.dto.CaptureWalletSettings
import com.trenching.bot.walletactivity.WalletHistoryCaptureLimits

/** Form defaults of the wallet + token scope; its verification needs more requests than the console's other wallet defaults. */
object CaptureDefaults {
    const val WINDOW_SLOTS = 4500L

    val walletToken = CaptureWalletSettings("", CaptureWalletScope.WALLET_TOKEN.value, WalletHistoryCaptureLimits.TOKEN_ACCOUNT_DEFAULT_TRANSACTIONS,
        WalletHistoryCaptureLimits.TOKEN_ACCOUNT_DEFAULT_SCAN_PAGES, "")
    val walletTokenBudget = CaptureBudget(requestBudget = CaptureValidation.WALLET_REQUESTS_MAX.toInt())

    /** The allowance one wallet operation of [scope] starts with. */
    fun walletBudget(scope: CaptureWalletScope?): CaptureBudget = when (scope) {
        CaptureWalletScope.WALLET_TOKEN -> walletTokenBudget
        CaptureWalletScope.PUMPFUN_USER_BUYS, CaptureWalletScope.WALLET_REFERENCES, null -> CaptureBudget()
    }

    fun config(rpcConfigured: Boolean, ratesConfigured: Boolean, suggested: CaptureSuggestedWallets, recent: List<CaptureRecentWallet>) = CaptureConfigView(
        rpcConfigured, ratesConfigured, CaptureWalletSettings(""), walletToken, walletTokenBudget, CaptureMarketSettings(), CaptureBudget(),
        CaptureValidation.bounds, WINDOW_SLOTS, suggested.items, recent,
    )
}
