package com.trenching.bot.console.capture

import com.trenching.bot.walletactivity.WalletHistoryScope

/** The console's wallet discovery scopes, by their HTTP [value], and the recorded scope each one captures. */
enum class CaptureWalletScope(val value: String) {
    PUMPFUN_USER_BUYS("pumpfun-user-buys"),
    WALLET_REFERENCES("wallet-references"),
    WALLET_TOKEN("wallet-token");

    fun historyScope(): WalletHistoryScope = when (this) {
        PUMPFUN_USER_BUYS -> WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR
        WALLET_REFERENCES -> WalletHistoryScope.WALLET_REFERENCES
        WALLET_TOKEN -> WalletHistoryScope.WALLET_TOKEN_ACCOUNT
    }

    companion object {
        fun of(value: String): CaptureWalletScope? = entries.firstOrNull { it.value == value }

        fun of(scope: WalletHistoryScope): CaptureWalletScope = when (scope) {
            WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR -> PUMPFUN_USER_BUYS
            WalletHistoryScope.WALLET_REFERENCES -> WALLET_REFERENCES
            WalletHistoryScope.WALLET_TOKEN_ACCOUNT -> WALLET_TOKEN
        }
    }
}
