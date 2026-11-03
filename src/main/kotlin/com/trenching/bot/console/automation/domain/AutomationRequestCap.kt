package com.trenching.bot.console.automation.domain

/**
 * Plan Rule 19: an acquisition operation starts only while the provider requests of the rolling day, manual captures included,
 * plus [operationRequests], the most that operation may spend over all its automatic retries, stay within [maxPerDay].
 */
data class AutomationRequestCap(val maxPerDay: Long, val operationRequests: Long) {
    fun admits(requestsLastDay: Long): Boolean = requestsLastDay + operationRequests <= maxPerDay

    /** Plan Rule 37: backfill discovery pauses once the rolling total is within [reserve] requests of the cap, so live capture keeps headroom. */
    fun admitsBackfillDiscovery(requestsLastDay: Long, reserve: Long): Boolean = requestsLastDay < maxPerDay - reserve

    /**
     * Plan Rule 19, amended 2026-11-03: a backfill's acquisition operation leaves the [reserve] free as well. The rolling total
     * plus [operationRequests] must stay within the cap less the reserve, so a backfill never spends the headroom live capture
     * keeps.
     */
    fun admitsBackfill(requestsLastDay: Long, reserve: Long): Boolean = requestsLastDay + operationRequests <= maxPerDay - reserve
}
