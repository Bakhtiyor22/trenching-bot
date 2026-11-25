package com.trenching.bot.replay.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.PageResult
import com.trenching.bot.replay.domain.RunQuoteTotal
import com.trenching.bot.replay.domain.RunSearchQuery
import com.trenching.bot.replay.domain.RunSearchResult
import com.trenching.bot.replay.domain.RunStatus
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunRepository
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunSearchRepository
import java.math.BigDecimal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SimulationRunSearchTransaction(
    private val search: SimulationRunSearchRepository,
    private val runs: SimulationRunRepository,
    private val summaries: RunSummaryAssembler,
) {
    /** The total and the totals are sums of the one status aggregate over the selected statuses, so they can never disagree. */
    @Transactional(readOnly = true)
    fun search(query: RunSearchQuery): RunSearchResult {
        val bounded = if (query.size == 0) query else query.copy(size = ReplayPaging.request(query.page, query.size).pageSize)
        val aggregates = search.statusAggregates(bounded)
        val counts = RunStatus.entries.associateWith { status -> aggregates.filter { it.status == status }.sumOf { it.runs } }
        val selected = aggregates.filter { bounded.statuses.isEmpty() || it.status in bounded.statuses }
        val total = selected.sumOf { it.runs }
        val totals = selected.groupBy { it.quoteMint }.toSortedMap().map { (quote, rows) ->
            RunQuoteTotal(quote, rows.sumOf { it.runs }, rows.fold(BigDecimal.ZERO.setScale(DecimalPolicy.SCALE)) { sum, row -> sum + row.realizedPnl })
        }
        if (bounded.size == 0) return RunSearchResult(PageResult(emptyList(), bounded.page, 0, total), counts, totals)
        val ids = search.pageIds(bounded)
        val rows = runs.findSummariesByIdIn(ids).associateBy { it.id }
        val items = summaries.assemble(ids.map { checkNotNull(rows[it]) { "Searched run vanished runId=$it" } })
        return RunSearchResult(PageResult(items, bounded.page, bounded.size, total), counts, totals)
    }
}
