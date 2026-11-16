package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import java.time.Instant
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The reads and writes of [AutomationHistoryStartService]: a page of ids, then one transaction for each item, so one that loses a race costs only itself. */
@Service
@ConditionalOnAutomationConsole
class AutomationHistoryStartTransaction(
    private val items: AutomationItemRepository,
    private val mapper: AutomationRecordMapper,
    private val events: AutomationItemEventWriter,
) {
    /** The ids of the next items after [afterId] that have no event, in id order. */
    @Transactional(readOnly = true)
    fun idsAfter(afterId: String): List<String> =
        items.findIdsWithoutEventsAfter(afterId, PageRequest.of(0, AutomationLimits.HISTORY_START_ITEMS_PER_PAGE))

    /** Gives the item its `HISTORY_STARTED`; false, changing nothing, when it has an event by the time it is locked or is gone. */
    @Transactional
    fun start(itemId: String, now: Instant): Boolean {
        val item = items.findOneById(itemId) ?: return false
        return events.startHistory(itemId, now, mapper.toStanding(item))
    }
}
