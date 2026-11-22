package com.trenching.bot.console.solana.accounts.domain

import java.time.Instant

/**
 * What the bot knows of one account: whether its credits are used up and the last balance read. A failed read keeps the last
 * [balance] and sets [balanceProblem]; [balanceCheckedAt] is the time of the last read, failed or not.
 */
data class RpcAccountState(
    val name: RpcAccountName,
    val outOfCreditsSince: Instant?,
    val balance: RpcAccountBalance?,
    val balanceCheckedAt: Instant?,
    val balanceProblem: BalanceProblem?,
    val updatedAt: Instant,
) {
    val hasCredits: Boolean get() = outOfCreditsSince == null

    companion object {
        fun fresh(name: RpcAccountName, at: Instant) = RpcAccountState(name, null, null, null, null, at)
    }
}
