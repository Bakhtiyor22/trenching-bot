package com.trenching.bot.console.solana.accounts.domain

/** The outcome of reading one account's balance: what the provider said, or why nothing could be read. */
sealed interface BalanceRead {
    data class Read(val balance: RpcAccountBalance) : BalanceRead

    data class Unavailable(val problem: BalanceProblem) : BalanceRead
}
