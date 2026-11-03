package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionRow
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemStateCountRow
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionEntity
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Session rows for a page of sessions: item counts and requests from one grouped query each, never one per session. */
@Service
@ConditionalOnAutomationConsole
class AutomationSessionRows(
    private val items: AutomationItemRepository,
    private val mapper: AutomationRecordMapper,
    private val properties: AutomationProperties,
) {
    @Transactional(readOnly = true)
    fun of(page: List<AutomationSessionEntity>): List<AutomationSessionRow> {
        if (page.isEmpty()) return emptyList()
        val ids = page.map { it.id }
        val counts = items.countBySessionAndState(ids).groupBy { it.sessionId }
        val requests = items.sumRequestsBySession(ids).associate { it.sessionId to it.requests }
        val calendar = properties.tradingCalendar()
        return page.map { session ->
            AutomationSessionRow(mapper.toSession(session), stateCounts(counts[session.id].orEmpty()), requests[session.id] ?: 0L,
                calendar.dayOf(session.firstBuyAt).date)
        }
    }

    private fun stateCounts(rows: List<AutomationItemStateCountRow>): Map<AutomationItemState, Long> {
        val byState = rows.associate { it.state to it.items }
        return AutomationItemState.entries.associateWith { byState[it] ?: 0L }
    }
}
