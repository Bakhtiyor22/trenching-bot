package com.trenching.bot.console.solana.accounts.domain

import java.time.Duration
import java.time.Instant

/**
 * How long an account that keeps failing after it returned must wait before it may return again (plan 20261122, R13). A return that is
 * marked out of credits again within an hour is a failed return: the account may not return for an hour, and each further failed return
 * doubles the wait, up to a day. A return that lasts an hour resets it. It lives in memory only.
 */
data class ReturnWait(val returnedAt: Instant?, val wait: Duration, val blockedUntil: Instant?) {
    /** [state] as it stands after this wait has had its say, with the wait that follows. */
    data class Settled(val state: RpcAccountState, val wait: ReturnWait)

    /** [proposed] is [old] after one change: a return inside the wait is refused, and an account going out is told apart from one that stays out. */
    fun settle(old: RpcAccountState, proposed: RpcAccountState, now: Instant): Settled = when {
        old.hasCredits && !proposed.hasCredits -> Settled(proposed, wentOut(now))
        !old.hasCredits && proposed.hasCredits -> returned(old, proposed, now)
        else -> Settled(proposed, this)
    }

    private fun returned(old: RpcAccountState, proposed: RpcAccountState, now: Instant): Settled =
        if (blockedUntil != null && now.isBefore(blockedUntil)) {
            Settled(proposed.copy(outOfCreditsSince = old.outOfCreditsSince), this)
        } else {
            Settled(proposed, copy(returnedAt = now))
        }

    private fun wentOut(now: Instant): ReturnWait {
        val lasted = returnedAt?.let { Duration.between(it, now) }
        if (lasted == null || lasted >= LASTING_RETURN) return NONE
        val next = if (wait.isZero) FIRST_WAIT else minOf(wait.multipliedBy(2), MAX_WAIT)
        return ReturnWait(null, next, now.plus(next))
    }

    companion object {
        val NONE = ReturnWait(null, Duration.ZERO, null)
        val LASTING_RETURN: Duration = Duration.ofHours(1)
        val FIRST_WAIT: Duration = Duration.ofHours(1)
        val MAX_WAIT: Duration = Duration.ofHours(24)
    }
}
