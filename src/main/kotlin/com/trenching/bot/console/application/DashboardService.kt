package com.trenching.bot.console.application

import com.trenching.bot.console.domain.DashboardSnapshot
import com.trenching.bot.console.domain.PositionCounts
import com.trenching.bot.console.domain.QuoteMintPnl
import com.trenching.bot.console.domain.RejectionCount
import com.trenching.bot.console.domain.ScenarioCounts
import com.trenching.bot.console.monitoring.ApplicationHealth
import com.trenching.bot.console.monitoring.RuntimeGaugeReader
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.domain.RunAggregates
import java.time.Clock
import com.trenching.bot.config.SimulationClock
import org.springframework.beans.factory.annotation.Qualifier
import java.time.Duration
import org.springframework.stereotype.Service

/** The monitoring dashboard's read model; every read is one bounded query or an in-memory meter lookup. */
@Service
class DashboardService(
    private val runs: SimulationRuns,
    private val orders: PaperOrderStore,
    private val health: ApplicationHealth,
    private val gauges: RuntimeGaugeReader,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    fun snapshot(): DashboardSnapshot {
        val now = clock.instant()
        val totals = runs.totals(now.minus(RUNS_WINDOW))
        return DashboardSnapshot(
            generatedAt = now,
            health = health.current(),
            totalRuns = totals.totalRuns,
            runsLast24h = totals.runsSince,
            recentRuns = runs.page(0, RECENT_RUNS, null).items,
            scenarios = ScenarioCounts(filled = totals.filledCount, rejected = totals.rejectedCount),
            positions = PositionCounts(open = totals.openCount, closed = totals.closedCount),
            realizedPnlByQuoteMint = realizedPnl(totals),
            rejections = rejections(),
            gauges = gauges.read(),
        )
    }

    private fun realizedPnl(totals: RunAggregates): List<QuoteMintPnl> =
        totals.realizedPnlByQuoteMint.map { (mint, pnl) -> QuoteMintPnl(mint, pnl) }.sortedBy { it.quoteMint.value }

    private fun rejections(): List<RejectionCount> {
        val counts = orders.rejectionCounts()
        return RejectionReason.ENTRY.map { RejectionCount(it, counts[it] ?: 0) }
    }

    private companion object {
        const val RECENT_RUNS = 20
        val RUNS_WINDOW: Duration = Duration.ofHours(24)
    }
}
