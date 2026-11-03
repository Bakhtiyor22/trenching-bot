package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.AutomationItemState

/** One item's id and state. */
interface AutomationItemStateRow {
    val id: String
    val state: AutomationItemState
}
