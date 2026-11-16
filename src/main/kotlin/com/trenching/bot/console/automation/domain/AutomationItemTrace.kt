package com.trenching.bot.console.automation.domain

/**
 * What a caller knows about an item's change that the item's progress alone does not show: the capture operation the item
 * followed ended in [endedOperationState] (the capture console's own state name), and whether the change charges a failed attempt
 * to a step, as opposed to an item failing without one (a capture deleted from the console, a backfill out of budget).
 */
data class AutomationItemTrace(val endedOperationState: String? = null, val attemptFailed: Boolean = false) {
    companion object {
        val NONE = AutomationItemTrace()
    }
}
