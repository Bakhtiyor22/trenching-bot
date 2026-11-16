package com.trenching.bot.console.automation.domain

/**
 * What one pass of starting histories did: items given their `HISTORY_STARTED`, items [skipped] because an event was there
 * already once the item was locked (the pipeline got there first), and items that [failed] and are left to the next pass.
 */
data class AutomationHistoryStartResult(val started: Int, val skipped: Int, val failed: Int)
