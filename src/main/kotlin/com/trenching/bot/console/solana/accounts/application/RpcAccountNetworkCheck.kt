package com.trenching.bot.console.solana.accounts.application

import com.trenching.bot.console.solana.accounts.domain.NetworkCheck
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName

/** Asks an account's RPC endpoint which network it serves, with one read-only `getGenesisHash` request. It never throws for a provider failure and is never called inside a database transaction. */
fun interface RpcAccountNetworkCheck {
    fun check(account: RpcAccountName): NetworkCheck
}
