package com.trenching.bot.console.solana.accounts.application

import com.trenching.bot.console.solana.accounts.domain.RpcAccountName

/** Asks an account's RPC endpoint whether it answers, with one read-only request. */
fun interface RpcAccountHealth {
    fun answers(account: RpcAccountName): Boolean
}
