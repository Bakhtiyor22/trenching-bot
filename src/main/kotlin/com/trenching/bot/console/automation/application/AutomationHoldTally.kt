package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.domain.AutomationDueItem
import com.trenching.bot.console.automation.domain.AutomationHoldContext
import com.trenching.bot.console.automation.domain.AutomationHoldKind
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationStepOutcome
import java.util.EnumMap

/** What one pipeline tick held and admitted per [AutomationHoldKind], reported to [AutomationHolds] once, when the tick ends. */
class AutomationHoldTally {
    private val held = EnumMap<AutomationHoldKind, Int>(AutomationHoldKind::class.java)
    private val admitted = EnumMap<AutomationHoldKind, Int>(AutomationHoldKind::class.java)
    private val contexts = EnumMap<AutomationHoldKind, AutomationHoldContext>(AutomationHoldKind::class.java)
    private val blocked = EnumMap<AutomationHoldKind, Int>(AutomationHoldKind::class.java)

    fun held(outcome: AutomationStepOutcome.Held) {
        held.merge(outcome.kind, 1, Int::plus)
        contexts[outcome.kind] = outcome.context
    }

    fun admitted(kind: AutomationHoldKind, context: AutomationHoldContext = AutomationHoldContext.NONE) {
        admitted.merge(kind, 1, Int::plus)
        contexts.putIfAbsent(kind, context)
    }

    /**
     * Counts the due items that stand `BLOCKED_BUDGET` per kind that waits for them: any of them may wait for the request cap, and a
     * backfill's also for the reserve. A kind not counted here clears only when an item of it is admitted.
     */
    fun blockedDue(due: List<AutomationDueItem>) {
        val standing = due.filter { it.progress.state == AutomationItemState.BLOCKED_BUDGET }
        blocked[AutomationHoldKind.REQUEST_CAP] = standing.size
        blocked[AutomationHoldKind.BACKFILL_RESERVE] = standing.count { it.backfillId != null }
    }

    fun reportTo(holds: AutomationHolds, kinds: Collection<AutomationHoldKind>) {
        kinds.forEach { kind -> holds.observe(kind, held[kind] ?: 0, admitted[kind] ?: 0, contexts[kind] ?: AutomationHoldContext.NONE, blocked[kind]) }
    }
}
