package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The read of [AutomationActivitySummary]: one grouped count, in one short read-only transaction. */
@Service
@ConditionalOnAutomationConsole
class AutomationSummaryTransaction(private val items: AutomationItemRepository) {
    /** The items still waiting for something (every state but a terminal one) counted by state; a state without items is absent. */
    @Transactional(readOnly = true)
    fun waitingItemsByState(): Map<AutomationItemState, Long> =
        items.countByState(AutomationItemState.NON_TERMINAL).associate { it.state to it.items }
}
