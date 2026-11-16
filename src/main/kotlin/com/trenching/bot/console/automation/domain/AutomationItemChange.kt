package com.trenching.bot.console.automation.domain

/** An item as it stood before a change, with its capture's request total: what a history start records for an item that had no events. */
data class AutomationItemStanding(val progress: AutomationItemProgress, val requestsUsed: Int?)

/**
 * The events one change gives an item. [before] is the item's standing before the change, which the writer records as the start of
 * its history when the item has no event yet; null for an item the change itself created, which has no history to start.
 */
data class AutomationItemChange(val before: AutomationItemStanding?, val events: List<AutomationItemEventDraft>) {
    companion object {
        fun found(events: List<AutomationItemEventDraft>) = AutomationItemChange(null, events)
    }
}
