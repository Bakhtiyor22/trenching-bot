package com.trenching.bot.replay

import com.trenching.bot.common.MintAddress
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.replay.domain.RunAggregates
import com.trenching.bot.replay.domain.SampleDataset
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.convention.TestBean

/** A context of its own (the extra property keeps it out of the shared cache); every test starts from empty tables. */
@SpringBootTest(properties = ["spring.application.name=simulation-run-aggregates"])
@ActiveProfiles("test")
@Tag("integration")
@Import(PostgresTestDatabaseConfiguration::class)
class SimulationRunAggregatesIntegrationTest {
    @Autowired private lateinit var runs: SimulationRuns
    @Autowired private lateinit var catalog: ReplayDatasetCatalog
    @Autowired private lateinit var dataSource: DataSource

    @TestBean(name = SimulationClock.NAME, methodName = "fixedClock", enforceOverride = true)
    private lateinit var clock: Clock

    @BeforeEach
    fun emptyTables() {
        dataSource.connection.use { connection ->
            val tables = connection.prepareStatement("select tablename from pg_tables where schemaname = current_schema()").use { statement ->
                statement.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
            }
            connection.createStatement().use { it.execute("truncate table ${tables.joinToString()} cascade") }
        }
    }

    private fun sampleId(): String = catalog.importSample(SampleDataset.WEEKLY_BUY_V1).dataset.id

    @Test
    fun `an empty database aggregates to zeros and no pnl`() {
        assertEquals(RunAggregates(0, 0, 0, 0, 0, 0, emptyMap()), runs.totals(Instant.EPOCH))
    }

    @Test
    fun `the sample's default run gives its exact totals`() {
        runs.start(sampleId(), SimulationConfig.mvp())

        val totals = runs.totals(Instant.EPOCH)

        assertEquals(RunAggregates(1, 1, 3, 0, 0, 3, mapOf(MintAddress.WRAPPED_SOL to BigDecimal("26.089701492540"))), totals)
        assertEquals("26.089701492540", totals.realizedPnlByQuoteMint.getValue(MintAddress.WRAPPED_SOL).toPlainString())
    }

    @Test
    fun `runs with different fees add up and another quote mint stays a separate entry`() {
        val datasetId = sampleId()
        val cheap = runs.start(datasetId, SimulationConfig.of(BigDecimal("100"), 10, 50, 2, 2, 5)).run
        val dear = runs.start(datasetId, SimulationConfig.of(BigDecimal("100"), 20, 50, 2, 2, 5)).run
        val other = runs.start(datasetId, SimulationConfig.of(BigDecimal("100"), 10, 50, 2, 2, 5, quoteMint = OTHER_MINT)).run

        val totals = runs.totals(Instant.EPOCH)

        assertEquals(3, totals.totalRuns)
        assertEquals(listOf(cheap, dear, other).sumOf { it.filledCount.toLong() }, totals.filledCount)
        assertEquals(listOf(cheap, dear, other).sumOf { it.rejectedCount.toLong() }, totals.rejectedCount)
        assertEquals(listOf(cheap, dear, other).sumOf { it.openCount.toLong() }, totals.openCount)
        assertEquals(listOf(cheap, dear, other).sumOf { it.closedCount.toLong() }, totals.closedCount)
        assertEquals(mapOf(MintAddress.WRAPPED_SOL to cheap.realizedPnl.add(dear.realizedPnl), OTHER_MINT to other.realizedPnl),
            totals.realizedPnlByQuoteMint)
        assertEquals(12, totals.realizedPnlByQuoteMint.getValue(MintAddress.WRAPPED_SOL).scale())
    }

    @Test
    fun `a run created exactly at the boundary counts and one before it does not`() {
        val run = runs.start(sampleId(), SimulationConfig.mvp()).run

        assertEquals(START, run.createdAt)
        assertEquals(1, runs.totals(START).runsSince)
        assertEquals(0, runs.totals(START.plus(ONE_MICRO)).runsSince)
        assertEquals(0, runs.totals(START.plusNanos(1)).runsSince)
        assertEquals(1, runs.totals(START.plusNanos(1)).totalRuns)
    }

    private companion object {
        @JvmStatic
        fun fixedClock(): Clock = Clock.fixed(START, ZoneOffset.UTC)

        val START: Instant = Instant.parse("2026-10-03T12:00:00.000001Z")
        val ONE_MICRO: Duration = Duration.ofNanos(1_000)
        val OTHER_MINT: MintAddress = MintAddress.of("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
    }
}
