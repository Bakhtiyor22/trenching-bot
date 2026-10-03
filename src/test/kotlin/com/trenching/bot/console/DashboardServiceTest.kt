package com.trenching.bot.console

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.PageResult
import com.trenching.bot.console.application.DashboardService
import com.trenching.bot.console.domain.HealthSnapshot
import com.trenching.bot.console.domain.PositionCounts
import com.trenching.bot.console.domain.QuoteMintPnl
import com.trenching.bot.console.domain.ScenarioCounts
import com.trenching.bot.console.domain.RejectionCount
import com.trenching.bot.console.monitoring.RuntimeGaugeReader
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.PaperFill
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.domain.RunAggregates
import com.trenching.bot.replay.domain.RunStart
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.strategy.domain.TradeIntent
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class DashboardServiceTest {
    private val now = Instant.parse("2026-10-03T12:00:00Z")
    private val runs = FakeSimulationRuns()
    private val orders = FakePaperOrderStore()
    private val service = DashboardService(runs, orders, { HealthSnapshot("UP", "UP") },
        RuntimeGaugeReader(SimpleMeterRegistry()), Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `an empty database gives zero totals, no recent runs and a zero count for every rejection reason`() {
        val snapshot = service.snapshot()

        assertEquals(now, snapshot.generatedAt)
        assertEquals(HealthSnapshot("UP", "UP"), snapshot.health)
        assertEquals(0, snapshot.totalRuns)
        assertEquals(0, snapshot.runsLast24h)
        assertEquals(ScenarioCounts(0, 0), snapshot.scenarios)
        assertEquals(PositionCounts(0, 0), snapshot.positions)
        assertEquals(emptyList(), snapshot.realizedPnlByQuoteMint)
        assertEquals(emptyList(), snapshot.recentRuns)
        assertEquals(RejectionReason.entries.map { RejectionCount(it, 0) }, snapshot.rejections)
        assertNull(snapshot.gauges.heapUsedBytes)
        assertNull(snapshot.gauges.httpRequestCount)
    }

    @Test
    fun `recent runs are the newest twenty and the total counts every run`() {
        runs.stored = (1..25).map { run("run-$it", now.minusSeconds(it.toLong())) }

        val snapshot = service.snapshot()

        assertEquals(25, snapshot.totalRuns)
        assertEquals((1..20).map { "run-$it" }, snapshot.recentRuns.map { it.id })
        assertEquals(listOf(Triple(0, 20, null as String?)), runs.pageRequests)
    }

    @Test
    fun `run kpis come from the stored totals with a 24 hour window from the clock`() {
        runs.aggregates = RunAggregates(7, 2, 15, 6, 4, 9, emptyMap())

        val snapshot = service.snapshot()

        assertEquals(listOf(now.minus(Duration.ofHours(24))), runs.totalsRequests)
        assertEquals(7, snapshot.totalRuns)
        assertEquals(2, snapshot.runsLast24h)
        assertEquals(ScenarioCounts(filled = 15, rejected = 6), snapshot.scenarios)
        assertEquals(PositionCounts(open = 4, closed = 9), snapshot.positions)
    }

    @Test
    fun `realized pnl stays per quote mint sorted by mint`() {
        val usdc = MintAddress.of("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
        runs.aggregates = RunAggregates(2, 0, 6, 0, 0, 6,
            linkedMapOf(MintAddress.WRAPPED_SOL to BigDecimal("26.089701492540"), usdc to BigDecimal("-1.500000000000")))

        val pnl = service.snapshot().realizedPnlByQuoteMint

        assertEquals(listOf(QuoteMintPnl(usdc, BigDecimal("-1.500000000000")),
            QuoteMintPnl(MintAddress.WRAPPED_SOL, BigDecimal("26.089701492540"))), pnl)
    }

    @Test
    fun `rejection counts keep the enum order and fill absent reasons with zero`() {
        orders.counts = mapOf(RejectionReason.ZERO_QUANTITY to 2, RejectionReason.MISSING_OBSERVATION to 5)

        val counts = service.snapshot().rejections.associate { it.reason to it.count }

        assertEquals(RejectionReason.entries, counts.keys.toList())
        assertEquals(5, counts[RejectionReason.MISSING_OBSERVATION])
        assertEquals(2, counts[RejectionReason.ZERO_QUANTITY])
        assertEquals(0, counts[RejectionReason.STALE_OBSERVATION])
    }

    private fun run(id: String, createdAt: Instant) = RunSummary(id, "dataset", "engine", "config", "result",
        MintAddress.WRAPPED_SOL, 3, 3, 0, 0, 3, BigDecimal("1.000000000000"), createdAt)

    private class FakeSimulationRuns : SimulationRuns {
        var stored: List<RunSummary> = emptyList()
        var aggregates: RunAggregates? = null
        val pageRequests = mutableListOf<Triple<Int, Int, String?>>()
        val totalsRequests = mutableListOf<Instant>()

        override fun start(datasetId: String, config: SimulationConfig): RunStart = error("not used")

        override fun page(page: Int, size: Int, datasetId: String?): PageResult<RunSummary> {
            pageRequests += Triple(page, size, datasetId)
            val newestFirst = stored.sortedByDescending { it.createdAt }
            return PageResult(newestFirst.drop(page * size).take(size), page, size, stored.size.toLong())
        }

        override fun find(runId: String): RunSummary? = stored.firstOrNull { it.id == runId }

        override fun report(runId: String): String = error("not used")

        override fun totals(createdSince: Instant): RunAggregates {
            totalsRequests += createdSince
            return aggregates ?: RunAggregates(stored.size.toLong(), stored.count { it.createdAt >= createdSince }.toLong(),
                0, 0, 0, 0, emptyMap())
        }
    }

    private class FakePaperOrderStore : PaperOrderStore {
        var counts: Map<RejectionReason, Long> = emptyMap()

        override fun record(runId: String, intents: List<TradeIntent>, outcomes: List<FillOutcome>) = error("not used")

        override fun outcomes(runId: String): List<FillOutcome> = error("not used")

        override fun fills(runId: String): List<PaperFill> = error("not used")

        override fun rejectionCounts(): Map<RejectionReason, Long> = counts
    }
}
