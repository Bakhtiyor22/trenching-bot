package com.trenching.bot.console.automation.domain

/**
 * Plan Rule 19: an acquisition operation starts only while the provider requests of the rolling day, manual captures included,
 * plus [operationRequests], the most that operation may spend over all its automatic retries, stay within [maxPerDay].
 */
data class AutomationRequestCap(val maxPerDay: Long, val operationRequests: Long) {
    fun admits(requestsLastDay: Long): Boolean = requestsLastDay + operationRequests <= maxPerDay
}
