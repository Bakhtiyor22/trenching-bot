package com.trenching.bot.console.automation.domain

/**
 * Plan Rules 37 and 39: a backfill's spend is its discovery requests plus its items' captures. A page of discovery or a capture
 * operation starts only if the spend plus the most it may cost stays within [requestBudget], so the budget is passed by no more
 * than the one operation's recovery allowance that was admitted on the last request counted.
 */
data class BackfillBudget(val requestBudget: Long, val discoveryRequests: Long, val itemRequests: Long) {
    val spent: Long get() = discoveryRequests + itemRequests

    fun admits(requests: Long): Boolean = spent + requests <= requestBudget
}
