package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.AutomationItemState

/** The number of items, over every session, in one state. */
interface AutomationItemStateTotalRow {
    val state: AutomationItemState
    val items: Long
}
