package com.trenching.bot.console.config

import com.trenching.bot.console.application.ConsoleAddresses
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The console HTTP API is registered only when [enabled]; it has no authentication, so it stays off in prod.
 * [suggestedWallets] are public wallets the capture form offers first.
 */
@ConfigurationProperties(ConsoleProperties.PREFIX)
data class ConsoleProperties(val enabled: Boolean = false, val suggestedWallets: List<SuggestedWallet> = emptyList()) {
    init {
        suggestedWallets.forEach { wallet ->
            require(wallet.label.isNotBlank() && wallet.label == wallet.label.trim() && wallet.label.length <= MAX_LABEL_LENGTH) {
                "$PREFIX.suggested-wallets labels must have 1 to $MAX_LABEL_LENGTH characters without surrounding spaces"
            }
            require(ConsoleAddresses.walletOrNull(wallet.address) != null) { "$PREFIX.suggested-wallets addresses must be base58 wallet addresses" }
        }
        require(suggestedWallets.distinctBy { it.address }.size == suggestedWallets.size) { "$PREFIX.suggested-wallets addresses must be distinct" }
    }

    data class SuggestedWallet(val label: String, val address: String)

    companion object {
        const val PREFIX = "trenching.console"
        private const val MAX_LABEL_LENGTH = 24
    }
}
