package com.trenching.bot.console.automation.domain

/** A session's capture health: [ISSUES] when an item failed or was abandoned, or a sweep entry was refused; [OK] otherwise. */
enum class SessionHealth {
    ISSUES,
    OK;

    companion object {
        /** The item states that mark a session's capture as having issues. */
        val ISSUE_STATES: Set<AutomationItemState> = setOf(AutomationItemState.FAILED, AutomationItemState.ABANDONED)
    }
}
