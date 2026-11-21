package com.trenching.bot.console.automation.domain

import java.time.Instant

/**
 * The numbers a hold's lines print: the rolling request total against the cap and the backfill reserve where they explain it, and the
 * capture console's [reason] for a refusal. A value that does not apply is null and left out of the line.
 */
data class AutomationHoldContext(
    val requestsLast24h: Long? = null,
    val cap: Long? = null,
    val reserveRequests: Long? = null,
    val reason: String? = null,
) {
    companion object {
        val NONE = AutomationHoldContext()
    }
}

/** A hold in force: what holds capture, since when, with [waiting] items held on its latest tick and its latest [context]. */
data class AutomationHold(val kind: AutomationHoldKind, val since: Instant, val waiting: Int, val context: AutomationHoldContext)
