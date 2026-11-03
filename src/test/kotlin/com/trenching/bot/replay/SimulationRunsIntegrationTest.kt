package com.trenching.bot.replay

import com.trenching.bot.common.MintAddress
import com.trenching.bot.replay.application.PersistedReplayService
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.*
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV5
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import com.trenching.bot.support.runRowCounts
import java.math.BigDecimal
import javax.sql.DataSource
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Import(PostgresTestDatabaseConfiguration::class)
class SimulationRunsIntegrationTest {
    @Autowired private lateinit var runs: SimulationRuns
    @Autowired private lateinit var catalog: ReplayDatasetCatalog
    @Autowired private lateinit var persistedReplay: PersistedReplayService
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var inputs: RunInputs

    private fun variant(tag: String): ReplayDataset =
        requireNotNull(javaClass.getResourceAsStream("/replay/samples/dip-sample-v3.json")).use { input ->
            ReplayDatasetLoader(json).load(input).let { dataset ->
                dataset.copy(referenceRates = dataset.referenceRates.map { it.copy(source = "variant-$tag") })
            }
        }

    private fun counts(runId: String): List<Int> = runRowCounts(dataSource, runId)

    @Test
    fun `the sample with the mvp config stores exact totals and restarts idempotently`() {
        val datasetId = catalog.importSample(SampleDataset.DIP_SAMPLE_V3).dataset.id
        val first = runs.start(datasetId, SimulationConfig.mvp())
        val run = first.run
        assertEquals(datasetId, run.datasetId)
        assertEquals(MintAddress.WRAPPED_SOL, run.quoteMint)
        assertEquals(listOf(10, 5, 5, 3, 2), listOf(run.scenarioCount, run.filledCount, run.rejectedCount, run.openCount, run.closedCount))
        assertEquals(com.trenching.bot.support.DipSample.TOTAL_PNL, run.realizedPnl.toPlainString())
        val stored = counts(run.id)
        val again = runs.start(datasetId, SimulationConfig.mvp())
        assertFalse(again.created)
        assertEquals(run, again.run)
        assertEquals(stored, counts(run.id))
        assertEquals(listOf(1, 11, 31, 10, 10, 5, 5), stored)
        assertEquals(run, runs.find(run.id))
        assertEquals(10, json.readTree(runs.report(run.id)).path("scenarios").size())
    }

    @Test
    fun `a new configuration creates a separate run once`() {
        val datasetId = catalog.importSample(SampleDataset.DIP_SAMPLE_V3).dataset.id
        val config = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, java.math.BigDecimal("10000"), 4500, 1, BigDecimal("0.0011"))
        val first = runs.start(datasetId, config)
        val second = runs.start(datasetId, config)
        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.run, second.run)
        assertNotEquals(runs.start(datasetId, SimulationConfig.mvp()).run.id, first.run.id)
    }

    @Test
    fun `an unknown dataset cannot start a run`() {
        assertFailsWith<DatasetNotFoundException> { runs.start("0".repeat(64), SimulationConfig.mvp()) }
    }

    @Test
    fun `an unknown run has no summary or report`() {
        assertNull(runs.find("0".repeat(64)))
        assertFailsWith<SimulationRunNotFoundException> { runs.report("0".repeat(64)) }
    }

    @Test
    fun `a run persisted directly registers its dataset under a default name`() {
        val record = persistedReplay.execute(variant("ledger"), SimulationConfig.mvp())
        val dataset = requireNotNull(catalog.find(record.datasetChecksum))
        assertEquals("dataset-" + record.datasetChecksum.take(12), dataset.name)
        assertEquals(record.datasetChecksum, requireNotNull(runs.find(record.id)).datasetId)
    }

    @Test
    fun `the command path registers the dataset under the file name`() {
        val content = ReplayDatasetWriter(json).write(variant("command"))
        val record = persistedReplay.execute("variant-command.json", content.byteInputStream(), SimulationConfig.mvp())
        assertEquals("variant-command.json", requireNotNull(catalog.find(record.datasetChecksum)).name)
        assertEquals(record.resultChecksum, requireNotNull(runs.find(record.id)).resultChecksum)
    }

    @Test
    fun `open positions store zero realized pnl at model scale`() {
        val original = variant("open")
        // Up to three slots after each buy, without the standing-curve cycle's take profit: every entry fills, and no exit can follow.
        val record = persistedReplay.execute(original.copy(observations = original.observations.filter {
            it.slot.value % 1000 <= 3 && it.slot.value != DipSample.STANDING_CURVE_TRIGGER_SLOT
        }),
            SimulationConfig.mvp())
        val run = requireNotNull(runs.find(record.id))
        assertEquals(listOf(5, 0, 5), listOf(run.filledCount, run.closedCount, run.openCount))
        assertEquals("0.000000000000", run.realizedPnl.toPlainString())
    }

    @Test
    fun `runs page by dataset newest first`() {
        val datasetId = catalog.register("paging", ReplayDatasetWriter(json).write(variant("paging")).byteInputStream()).dataset.id
        runs.start(datasetId, SimulationConfig.mvp())
        runs.start(datasetId, SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, java.math.BigDecimal("10000"), 4500, 1, BigDecimal("0.0012")))
        val page = runs.page(0, 1000, datasetId)
        assertEquals(100, page.size)
        assertEquals(2L, page.total)
        assertTrue(page.items.all { it.datasetId == datasetId })
        assertEquals(page.items.sortedWith(compareByDescending<RunSummary> { it.createdAt }.thenBy { it.id }), page.items)
        assertTrue(runs.page(0, 10, null).total >= 2)
    }

    @Test
    fun `a v5 dataset runs the configurations decided within its coverage and refuses the rest, storing nothing`() {
        val takeProfit = DipSample.pair(DipSample.TAKE_PROFIT)
        val noDip = DipSample.pair(DipSample.NO_DIP)
        val decided = DipSampleV5.dataset(mapOf(takeProfit to 1006, noDip to 5000))
        val datasetId = catalog.register("decided-v5", DipSampleV5.json(decided).byteInputStream()).dataset.id
        val runsBefore = runCount()
        val wideTakeProfit = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 5000, 1500, BigDecimal("10000"), 4500, 1, BigDecimal("0.001"))

        val refused = assertFailsWith<MarketCoverageEndedException> { runs.start(datasetId, wideTakeProfit) }

        assertTrue(refused.message.orEmpty().contains(takeProfit.base.value), refused.message)
        assertTrue(refused.message.orEmpty().contains("decidedAt=5500") && refused.message.orEmpty().contains("throughSlot=1006"), refused.message)
        assertFailsWith<MarketCoverageEndedException> { runs.start(datasetId, SimulationConfig.mvp()) }
        assertEquals(runsBefore, runCount())
        val covered = catalog.register("decided-v5-tp", DipSampleV5.json(decided.copy(
            transactions = decided.transactions.filter { it.pair == takeProfit },
            observations = decided.observations.filter { it.pair == takeProfit },
            coverage = decided.coverage.filter { it.pair == takeProfit }))
            .byteInputStream()).dataset.id
        assertTrue(runs.start(covered, SimulationConfig.mvp()).created)
        assertEquals(runsBefore + 1, runCount())
    }

    @Test
    fun `a dataset-referenced run stores no dataset copy and reads the same inputs as a copy run of that dataset`() {
        val datasetId = catalog.register("reference", ReplayDatasetWriter(json).write(variant("reference")).byteInputStream()).dataset.id
        val copy = runs.start(datasetId, SimulationConfig.mvp()).run
        val started = runs.start(datasetId, REFERENCE_CONFIG, RunStorage.DATASET_REFERENCE)
        val reference = started.run

        assertTrue(started.created)
        assertEquals(RunStorage.COPY, copy.storage)
        assertEquals(RunStorage.DATASET_REFERENCE, reference.storage)
        assertEquals(reference, runs.find(reference.id))
        assertEquals(listOf(1, 11, 0), counts(reference.id).take(3))
        assertEquals(reference.scenarioCount, counts(reference.id)[3])
        assertEquals(1, count("select count(*) from simulation_runs where id = ? and dataset_snapshot is null", reference.id))
        assertEquals(0, count("select count(*) from simulation_runs where id = ? and dataset_snapshot is null", copy.id))
        assertEquals(31, inputs.observations(reference.id).size)
        assertEquals(inputs.observations(copy.id), inputs.observations(reference.id))
        assertEquals(inputs.transactions(copy.id), inputs.transactions(reference.id))
        assertEquals(0, count("select count(*) from simulation_runs where id in (?, ?) and not ($STORAGE_CHECK)", copy.id, reference.id))
    }

    @Test
    fun `run identity and result do not depend on storage, and a repeat start returns the run as stored`() {
        val datasetId = catalog.register("storage-identity", ReplayDatasetWriter(json).write(variant("storage-identity")).byteInputStream()).dataset.id
        val expected = ReplayRunner().run(catalog.load(datasetId), REFERENCE_CONFIG)
        val reference = runs.start(datasetId, REFERENCE_CONFIG, RunStorage.DATASET_REFERENCE).run
        val copy = runs.start(datasetId, SimulationConfig.mvp(), RunStorage.COPY).run
        val stored = counts(reference.id)

        val referenceAgain = runs.start(datasetId, REFERENCE_CONFIG, RunStorage.COPY)
        val copyAgain = runs.start(datasetId, SimulationConfig.mvp(), RunStorage.DATASET_REFERENCE)

        assertEquals(expected.runId, reference.id)
        assertEquals(expected.resultChecksum, reference.resultChecksum)
        assertFalse(referenceAgain.created)
        assertEquals(reference, referenceAgain.run)
        assertFalse(copyAgain.created)
        assertEquals(copy, copyAgain.run)
        assertEquals(stored, counts(reference.id))
        assertEquals(1, count("select count(*) from simulation_runs where id = ? and dataset_snapshot is null", reference.id))
    }

    @Test
    fun `a run stored before run storage existed reads as a copy`() {
        val datasetId = catalog.register("legacy-storage", ReplayDatasetWriter(json).write(variant("legacy-storage")).byteInputStream()).dataset.id
        val run = runs.start(datasetId, SimulationConfig.mvp()).run
        dataSource.connection.use { connection ->
            connection.prepareStatement("update simulation_runs set storage = null where id = ?").use { it.setString(1, run.id); it.executeUpdate() }
        }

        assertEquals(RunStorage.COPY, requireNotNull(runs.find(run.id)).storage)
        assertEquals(31, inputs.observations(run.id).size)
        assertEquals(0, count("select count(*) from simulation_runs where id = ? and not ($STORAGE_CHECK)", run.id))
    }

    private fun count(sql: String, vararg parameters: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
        }
    }

    private fun runCount(): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("select count(*) from simulation_runs").use { statement ->
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
        }
    }

    private companion object {
        val REFERENCE_CONFIG: SimulationConfig =
            SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1, BigDecimal("0.0013"))

        /** `ck_simulation_runs_storage` from docs/schema/V20261102120000__automation.sql, which Hibernate does not create. */
        const val STORAGE_CHECK = "(storage is null or storage = 'COPY') and dataset_snapshot is not null " +
            "or storage = 'DATASET_REFERENCE' and dataset_snapshot is null"
    }
}
