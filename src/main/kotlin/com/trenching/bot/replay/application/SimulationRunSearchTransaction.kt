package com.trenching.bot.replay.application

import com.trenching.bot.common.PageResult
import com.trenching.bot.replay.domain.RunSearchQuery
import com.trenching.bot.replay.domain.RunSearchResult
import com.trenching.bot.replay.domain.RunStatus
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunRepository
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunSearchRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SimulationRunSearchTransaction(
    private val search: SimulationRunSearchRepository,
    private val runs: SimulationRunRepository,
    private val summaries: RunSummaryAssembler,
) {
    /** The total is the sum of the status counts of the selected statuses, so the two can never disagree. */
    @Transactional(readOnly = true)
    fun search(query: RunSearchQuery): RunSearchResult {
        val paging = ReplayPaging.request(query.page, query.size)
        val bounded = query.copy(size = paging.pageSize)
        val counts = search.statusCounts(bounded)
        val total = bounded.statuses.ifEmpty { RunStatus.entries.toSet() }.sumOf { counts.getValue(it) }
        val ids = search.pageIds(bounded)
        val rows = runs.findSummariesByIdIn(ids).associateBy { it.id }
        val items = summaries.assemble(ids.map { checkNotNull(rows[it]) { "Searched run vanished runId=$it" } })
        return RunSearchResult(PageResult(items, bounded.page, bounded.size, total), counts)
    }
}
