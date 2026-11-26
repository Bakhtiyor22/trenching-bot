package com.trenching.bot.console.api.dto

import java.time.Instant
import java.time.LocalDate

/** The RPC accounts and the one in use (`primary`, `backup`, or null while none has credits). Never an endpoint, key or project id. */
data class RpcAccountsResponse(
    val active: String?,
    val accounts: List<RpcAccountResponse>,
)

/** One account: [state] is ACTIVE, STANDBY, OUT_OF_CREDITS or NOT_CONFIGURED; [host] is the endpoint's host name only; the credits are the last balance read. */
data class RpcAccountResponse(
    val name: String,
    val configured: Boolean,
    val state: String,
    val host: String?,
    val creditsRemaining: Long?,
    val creditsUsed: Long?,
    val creditsLimit: Long?,
    val cycleEndsOn: LocalDate?,
    val plan: String?,
    val balanceCheckedAt: Instant?,
    val balanceProblem: String?,
    val outOfCreditsSince: Instant?,
)
