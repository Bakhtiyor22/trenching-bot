package com.trenching.bot.console.solana.accounts.domain

import java.time.LocalDate

/** An account's credits as its provider reports them, in whole credits; the billing cycle ends on [cycleEndsOn] (UTC). */
data class RpcAccountBalance(
    val creditsRemaining: Long,
    val creditsUsed: Long,
    val creditsLimit: Long,
    val cycleEndsOn: LocalDate,
    val plan: String,
) {
    init {
        require(creditsRemaining >= 0 && creditsUsed >= 0 && creditsLimit >= 0) { "Credits are never negative" }
    }
}
