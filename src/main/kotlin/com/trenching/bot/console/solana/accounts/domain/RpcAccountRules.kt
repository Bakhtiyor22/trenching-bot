package com.trenching.bot.console.solana.accounts.domain

import java.math.BigInteger
import java.time.Instant

/** The rules that decide which account is active and when an account goes out of credits, returns, or runs low (plan 20261122, Rules 11, 12, 14 and 16). */
object RpcAccountRules {
    private val HUNDRED = BigInteger.valueOf(100)

    /**
     * The first configured account that has credits and is on mainnet as far as this process knows; the primary is always preferred when
     * both qualify. Null when none does, which holds capture.
     */
    fun active(configs: RpcAccountConfigs, states: Map<RpcAccountName, RpcAccountState>, networks: Map<RpcAccountName, AccountNetwork>): RpcAccountName? =
        configs.configured.firstOrNull { states.getValue(it.name).hasCredits && networks.getValue(it.name) == AccountNetwork.MAINNET }?.name

    /** The first configured account that has credits, whatever is known of its network: where a restart assumes the last message left capture. */
    fun withCredits(configs: RpcAccountConfigs, states: Map<RpcAccountName, RpcAccountState>): RpcAccountName? =
        configs.configured.firstOrNull { states.getValue(it.name).hasCredits }?.name

    /**
     * [state] after a balance read at [now]. A read of zero credits marks the account out of credits (keeping the time it first ran out);
     * at least [resumeCredits] on an account that is out brings it back; any other read leaves that mark alone. A failed read keeps the
     * last balance and records the problem.
     */
    fun afterRead(state: RpcAccountState, read: BalanceRead, now: Instant, resumeCredits: Long): RpcAccountState = when (read) {
        is BalanceRead.Unavailable -> state.copy(balanceCheckedAt = now, balanceProblem = read.problem, updatedAt = now)
        is BalanceRead.Read -> state.copy(
            outOfCreditsSince = outOfCreditsAfter(state.outOfCreditsSince, read.balance.creditsRemaining, resumeCredits, now),
            balance = read.balance,
            balanceCheckedAt = now,
            balanceProblem = null,
            updatedAt = now,
        )
    }

    /** [state] after the account answered a request again although its balance could not say so. */
    fun afterAnswer(state: RpcAccountState, now: Instant): RpcAccountState =
        if (state.hasCredits) state else state.copy(outOfCreditsSince = null, updatedAt = now)

    /** [state] after the provider said its credits are used up. Unchanged when it was out already, so a repeat report changes nothing. */
    fun afterExhausted(state: RpcAccountState, now: Instant): RpcAccountState =
        if (state.hasCredits) state.copy(outOfCreditsSince = now, updatedAt = now) else state

    /** `remaining * 100 < limit * percent`, in integers; an account with no limit is never low. */
    fun isLow(balance: RpcAccountBalance, lowBalancePercent: Int): Boolean =
        balance.creditsLimit > 0 &&
            BigInteger.valueOf(balance.creditsRemaining) * HUNDRED < BigInteger.valueOf(balance.creditsLimit) * BigInteger.valueOf(lowBalancePercent.toLong())

    private fun outOfCreditsAfter(since: Instant?, remaining: Long, resumeCredits: Long, now: Instant): Instant? = when {
        remaining == 0L -> since ?: now
        since != null && remaining >= resumeCredits -> null
        else -> since
    }
}
