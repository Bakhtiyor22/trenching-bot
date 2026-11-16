package com.trenching.bot.console.automation.infrastructure.persistence

/** The order of the last history event of one item. */
interface AutomationItemEventSeqRow {
    val itemId: String
    val seq: Int
}
