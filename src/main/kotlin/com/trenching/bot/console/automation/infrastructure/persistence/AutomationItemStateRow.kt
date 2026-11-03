package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.AutomationItemState

/** One item's id, session, state and, when it failed, why. */
interface AutomationItemStateRow {
    val id: String
    val sessionId: String
    val state: AutomationItemState
    val failureCode: String?
}
