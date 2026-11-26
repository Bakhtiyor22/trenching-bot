package com.trenching.bot.console.solana.accounts.application

import com.trenching.bot.console.solana.accounts.domain.BalanceRead
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName

/** Reads an account's credits from its provider. It never throws for a provider failure and is never called inside a database transaction. */
fun interface RpcAccountBalances {
    fun read(account: RpcAccountName): BalanceRead
}
