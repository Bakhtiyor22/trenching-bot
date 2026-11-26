package com.trenching.bot.console.solana.accounts.application

import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import com.trenching.bot.console.solana.accounts.domain.RpcAccountState

/** How an account stands: [ACTIVE] takes the requests, [STANDBY] has credits and waits behind the active one, [WRONG_NETWORK] answers for a network other than mainnet and is never used. */
enum class RpcAccountStanding { ACTIVE, STANDBY, OUT_OF_CREDITS, WRONG_NETWORK, NOT_CONFIGURED }

/** One account as the console shows it. [host] is the endpoint's host name only. */
data class RpcAccountStatus(val name: RpcAccountName, val standing: RpcAccountStanding, val host: String?, val state: RpcAccountState)

/** The accounts in preference order and the one in use, null when none has credits. */
data class RpcAccountsSnapshot(val active: RpcAccountName?, val accounts: List<RpcAccountStatus>)
