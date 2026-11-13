package com.trenching.bot.console.automation.domain

import com.trenching.bot.console.automation.domain.AutomationItemState.ABANDONED
import com.trenching.bot.console.automation.domain.AutomationItemState.BLOCKED_BUDGET
import com.trenching.bot.console.automation.domain.AutomationItemState.CAPTURED
import com.trenching.bot.console.automation.domain.AutomationItemState.CAPTURING
import com.trenching.bot.console.automation.domain.AutomationItemState.DISCOVERED
import com.trenching.bot.console.automation.domain.AutomationItemState.FAILED
import com.trenching.bot.console.automation.domain.AutomationItemState.SIMULATED
import com.trenching.bot.console.automation.domain.AutomationItemState.SKIPPED_NOT_FIRST_BUY
import com.trenching.bot.console.automation.domain.AutomationItemState.WAITING

/**
 * Plan Rule 13: the state changes an item may make. Any other one is refused with [IllegalItemTransitionException]. A person's
 * retry of a `FAILED` item (`retry = true`) may also take it back into capture or, for a failed sweep, to `CAPTURED`; the
 * pipeline never passes `retry`, so it cannot leave `FAILED`.
 */
object AutomationItemTransitions {
    fun require(itemId: String, from: AutomationItemState, to: AutomationItemState, retry: Boolean = false) {
        val permitted = allowed(from) + if (retry) retried(from) else emptySet()
        if (to !in permitted) throw IllegalItemTransitionException(itemId, from, to)
    }

    private fun retried(from: AutomationItemState): Set<AutomationItemState> =
        if (from == FAILED) setOf(CAPTURING, CAPTURED) else emptySet()

    private fun allowed(from: AutomationItemState): Set<AutomationItemState> = when (from) {
        DISCOVERED -> setOf(CAPTURING, FAILED, ABANDONED)
        WAITING -> setOf(WAITING, CAPTURING, BLOCKED_BUDGET, FAILED, ABANDONED)
        BLOCKED_BUDGET -> setOf(WAITING, CAPTURING, FAILED, ABANDONED)
        CAPTURING -> setOf(CAPTURING, WAITING, BLOCKED_BUDGET, CAPTURED, FAILED, SKIPPED_NOT_FIRST_BUY, ABANDONED)
        CAPTURED -> setOf(CAPTURED, SIMULATED, FAILED, ABANDONED)
        SIMULATED, FAILED, ABANDONED, SKIPPED_NOT_FIRST_BUY -> emptySet()
    }
}
