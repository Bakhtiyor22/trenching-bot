package com.trenching.bot.console.automation.infrastructure.persistence

/** The sweep rows of one item: [entries] in all and, of them, the [ran] that have a run (the others were refused by the replay). */
interface AutomationSweepTallyRow {
    val entries: Long
    val ran: Long
}
