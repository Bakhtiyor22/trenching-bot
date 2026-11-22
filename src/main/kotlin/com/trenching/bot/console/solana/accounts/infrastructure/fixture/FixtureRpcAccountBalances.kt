package com.trenching.bot.console.solana.accounts.infrastructure.fixture

import com.trenching.bot.console.solana.accounts.application.RpcAccountBalances
import com.trenching.bot.console.solana.accounts.domain.BalanceProblem
import com.trenching.bot.console.solana.accounts.domain.BalanceRead
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import java.util.concurrent.ConcurrentHashMap

/** Balances that are set by hand, for tests and for running the bot with no provider: an account with no entry reads as having no project id. */
class FixtureRpcAccountBalances(initial: Map<RpcAccountName, BalanceRead> = emptyMap()) : RpcAccountBalances {
    private val reads = ConcurrentHashMap(initial)

    fun set(account: RpcAccountName, read: BalanceRead) {
        reads[account] = read
    }

    override fun read(account: RpcAccountName): BalanceRead = reads[account] ?: BalanceRead.Unavailable(BalanceProblem.NO_PROJECT_ID)
}
