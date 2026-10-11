package com.trenching.bot.walletactivity.infrastructure.decoder

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.domain.TargetBalanceChange
import tools.jackson.databind.JsonNode

/** How a captured transaction moved the target mint, for the token-account first buy. */
internal object TargetTokenBalances {
    /** Whether [tokenAccount]'s balance of [mint] rose; an account that is not among the keys exactly once is not credited. */
    fun credits(transaction: JsonNode, tokenAccount: String, mint: MintAddress): Boolean {
        val accountKeys = transaction.path("transaction").path("message").path("accountKeys")
        val index = accountKeys.withIndex().filter { (_, key) -> key.path("pubkey").let { it.isString && it.asString() == tokenAccount } }
            .singleOrNull()?.index ?: return false
        return (WalletTokenBalances.accountDelta(transaction.path("meta"), index, mint)?.signum() ?: 0) > 0
    }

    /** The change of the wallet's total balance of [mint] across its token accounts. */
    fun walletChange(transaction: JsonNode, wallet: WalletAddress, mint: MintAddress): TargetBalanceChange =
        when (val change = WalletTokenBalances.change(transaction.path("meta"), wallet, mint)) {
            is WalletTokenBalanceChange.Measured -> if (change.delta.signum() > 0) TargetBalanceChange.INCREASED else TargetBalanceChange.NOT_INCREASED
            is WalletTokenBalanceChange.Unproven -> TargetBalanceChange.UNMEASURABLE
        }
}
