package com.trenching.bot.console.automation.infrastructure.persistence

/** One session's provider requests, summed over its items. */
interface AutomationSessionRequestsRow {
    val sessionId: String
    val requests: Long
}
