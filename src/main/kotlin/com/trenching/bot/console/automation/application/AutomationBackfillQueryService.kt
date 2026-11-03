package com.trenching.bot.console.automation.application

import com.trenching.bot.common.PageResult
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationBackfillRow
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.BackfillNotFoundException
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import org.springframework.data.domain.Page
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The backfill read API. A page reads its items' requests and state counts with one grouped query each, never one per row, and
 * holds at most [AutomationPaging.MAX_PAGE_SIZE] rows.
 */
@Service
@ConditionalOnAutomationConsole
@Transactional(readOnly = true)
class AutomationBackfillQueryService(
    private val backfills: AutomationBackfillRepository,
    private val items: AutomationItemRepository,
    private val mapper: AutomationRecordMapper,
    private val properties: AutomationProperties,
) {
    /** The wallet's backfills, newest first. */
    fun list(page: Int, size: Int): PageResult<AutomationBackfillRow> {
        val pageable = AutomationPaging.pageable(page, size)
        val found: Page<AutomationBackfillEntity>? = properties.wallet?.let { backfills.findAllByWalletOrderByCreatedAtDescIdDesc(it, pageable) }
        return PageResult(rows(found?.content.orEmpty()), page, pageable.pageSize, found?.totalElements ?: 0)
    }

    fun get(id: String): AutomationBackfillRow {
        val backfill = backfills.findOneById(id) ?: throw BackfillNotFoundException(id)
        return rows(listOf(backfill)).single()
    }

    private fun rows(page: List<AutomationBackfillEntity>): List<AutomationBackfillRow> {
        if (page.isEmpty()) return emptyList()
        val ids = page.map { it.id }
        val requests = items.sumRequestsByBackfill(ids).associate { it.backfillId to it.requests }
        val counts = items.countByBackfillAndState(ids).groupBy { it.backfillId }
        return page.map { backfill ->
            val byState = counts[backfill.id].orEmpty().associate { it.state to it.items }
            AutomationBackfillRow(mapper.toBackfill(backfill), requests[backfill.id] ?: 0L, AutomationItemState.entries.associateWith { byState[it] ?: 0L })
        }
    }
}
