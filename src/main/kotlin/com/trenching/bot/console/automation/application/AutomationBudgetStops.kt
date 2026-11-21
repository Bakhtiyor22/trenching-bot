package com.trenching.bot.console.automation.application

/**
 * The backfills whose request budget stopped items on one pipeline tick, with how many each stopped and whether the tick stopped the
 * first item of its run: a backfill says its budget is spent once, on that tick, not on every tick that stops another item.
 */
class AutomationBudgetStops {
    private val stops = linkedMapOf<String, BudgetStop>()

    /** Counts an item stopped for [backfillId]; [firstOfBackfill] is true when no earlier item of it had been stopped by its budget. */
    fun stopped(backfillId: String, firstOfBackfill: Boolean) {
        val stop = stops.getOrPut(backfillId) { BudgetStop() }
        stop.items++
        stop.first = stop.first || firstOfBackfill
    }

    /** The backfills this tick stopped for the first time, with the items it stopped for each. */
    fun firstStops(): Map<String, Int> = stops.filterValues { it.first }.mapValues { it.value.items }

    private class BudgetStop {
        var items = 0
        var first = false
    }
}
