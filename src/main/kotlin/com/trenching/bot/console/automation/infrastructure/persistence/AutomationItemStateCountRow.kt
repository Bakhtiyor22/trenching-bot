package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.AutomationItemState

/** One session's number of items in one state. */
interface AutomationItemStateCountRow {
    val sessionId: String
    val state: AutomationItemState
    val items: Long
}
