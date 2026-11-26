package com.trenching.bot.console.solana.accounts.application

import com.trenching.bot.console.solana.accounts.domain.RpcAccountState

/** The `rpc_accounts` rows as account states. It opens no transaction of its own: [RpcAccountTransaction] does. */
interface RpcAccountRows {
    fun all(): List<RpcAccountState>

    /** Inserts the row of [state] or overwrites the one it has. */
    fun write(state: RpcAccountState)
}
