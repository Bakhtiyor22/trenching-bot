package com.trenching.bot.replay

import com.trenching.bot.replay.application.CopySnapshotIndexReader
import com.trenching.bot.replay.application.ReferenceIndexCache
import com.trenching.bot.replay.application.RunSearchIndexBackfillService
import com.trenching.bot.replay.application.RunSearchIndexWriter
import com.trenching.bot.replay.application.RunSearchIndexBackfillTransaction
import com.trenching.bot.replay.domain.RunSearchIndex
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.domain.SampleDataset
import com.trenching.bot.replay.infrastructure.persistence.ReplayDatasetRepository
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunRepository
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunTokenRepository
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import com.trenching.bot.support.RunSearchFixtures
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** A context of its own: every test starts from empty tables, stores runs, then strips their index as an old run would lack it. */
@SpringBootTest(properties = ["spring.application.name=run-search-index-backfill"])
@ActiveProfiles("test")
@Tag("integration")
@Import(PostgresTestDatabaseConfiguration::class)
class RunSearchIndexBackfillIntegrationTest {
    @Autowired private lateinit var runs: SimulationRuns
    @Autowired private lateinit var catalog: ReplayDatasetCatalog
    @Autowired private lateinit var backfill: RunSearchIndexBackfillService
    @Autowired private lateinit var pages: RunSearchIndexBackfillTransaction
    @Autowired private lateinit var runRepository: SimulationRunRepository
    @Autowired private lateinit var tokenRepository: SimulationRunTokenRepository
    @Autowired private lateinit var datasetRepository: ReplayDatasetRepository
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var snapshotReader: CopySnapshotIndexReader
    @Autowired private lateinit var indexWriter: RunSearchIndexWriter
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun emptyTables() {
        RunSearchFixtures.emptyTables(dataSource)
    }

    @Test
    fun `a run is indexed at persist with its dataset's mints and anchor`() {
        val datasetId = catalog.importSample(SampleDataset.DIP_SAMPLE_V3).dataset.id
        val run = runs.start(datasetId, config("0.001")).run

        assertEquals(expected(datasetId).mints.map { it.value }.sorted(), run.mints.map { it.value })
        assertEquals(ANCHOR, run.anchorBlockTime)
        assertNotNull(runRepository.findOneById(run.id)?.searchIndexedAt)
    }

    @Test
    fun `copy and reference runs get the same tokens and anchor as at persist, and a rerun changes nothing`() {
        val datasetId = catalog.importSample(SampleDataset.DIP_SAMPLE_V3).dataset.id
        val copy = runs.start(datasetId, config("0.001"), RunStorage.COPY).run
        val reference = runs.start(datasetId, config("0.002"), RunStorage.DATASET_REFERENCE).run
        strip(copy.id)
        strip(reference.id)

        backfill.backfill()

        listOf(copy.id, reference.id).forEach { id ->
            val indexed = checkNotNull(runs.find(id))
            assertEquals(expected(datasetId).mints.map { it.value }.sorted(), indexed.mints.map { it.value }, id)
            assertEquals(ANCHOR, indexed.anchorBlockTime, id)
        }
        val indexedAt = indexedAt(copy.id)
        val tokenRows = tokenRepository.count()

        backfill.backfill()

        assertEquals(tokenRows, tokenRepository.count())
        assertEquals(indexedAt, indexedAt(copy.id))
        assertEquals(0, pages.indexPage("", ReferenceIndexCache()).let { it.indexed + it.skipped + it.failed })
    }

    @Test
    fun `a run whose dataset is gone gets no tokens, is marked indexed and is not processed again`() {
        val fixtures = RunSearchFixtures(runRepository, tokenRepository, datasetRepository)
        fixtures.run("orphan", Instant.parse("2026-11-10T00:00:00Z"), datasetId = "gone")
        strip("orphan")

        val first = pages.indexPage("", ReferenceIndexCache())

        assertEquals(1, first.skipped)
        assertEquals(0, first.indexed)
        assertEquals(emptyList(), checkNotNull(runs.find("orphan")).mints)
        assertNull(runs.find("orphan")?.anchorBlockTime)
        assertNotNull(indexedAt("orphan"))
        assertNull(pages.indexPage("", ReferenceIndexCache()).lastId)
    }

    @Test
    fun `a run whose snapshot cannot be read stays unindexed while the others are indexed`() {
        val datasetId = catalog.importSample(SampleDataset.DIP_SAMPLE_V3).dataset.id
        val good = runs.start(datasetId, config("0.001")).run
        val bad = runs.start(datasetId, config("0.002")).run
        strip(good.id)
        strip(bad.id)
        update("update simulation_runs set dataset_snapshot = '{\"transactions\":[{}]}' where id = '${bad.id}'")

        val page = pages.indexPage("", ReferenceIndexCache())

        assertEquals(1, page.indexed)
        assertEquals(1, page.failed)
        assertNotNull(indexedAt(good.id))
        assertNull(indexedAt(bad.id))
        assertNotNull(runRepository.findOneById(bad.id)?.searchIndexFailedAt, "the attempt is recorded")
        assertNull(pages.indexPage("", ReferenceIndexCache()).lastId, "and the run is not read again")
    }

    @Test
    fun `a snapshot with an unparsable instant or an overflowing number is counted as failed and the runs after it are indexed`() {
        val datasetId = catalog.importSample(SampleDataset.DIP_SAMPLE_V3).dataset.id
        val ids = listOf("0.001", "0.002", "0.003", "0.004").map { runs.start(datasetId, config(it)).run.id }.sorted()
        ids.forEach(::strip)
        update("update simulation_runs set dataset_snapshot = '$UNPARSABLE_INSTANT' where id = '${ids[0]}'")
        update("update simulation_runs set dataset_snapshot = '$OVERFLOWING_SLOT' where id = '${ids[1]}'")

        val page = pages.indexPage("", ReferenceIndexCache())

        assertEquals(2, page.failed)
        assertEquals(2, page.indexed)
        assertEquals(listOf(null, null), ids.take(2).map(::indexedAt))
        assertEquals(listOf(true, true), ids.take(2).map { runRepository.findOneById(it)?.searchIndexFailedAt != null })
        ids.drop(2).forEach { id ->
            assertNotNull(indexedAt(id), id)
            assertEquals(expected(datasetId).mints.map { it.value }.sorted(), checkNotNull(runs.find(id)).mints.map { it.value }, id)
        }
        val repeat = pages.indexPage("", ReferenceIndexCache())
        assertEquals(0, repeat.let { it.indexed + it.skipped + it.failed })
        assertNull(repeat.lastId)
    }

    @Test
    fun `a referenced dataset is parsed once across the pages of a pass`() {
        val datasetId = catalog.importSample(SampleDataset.DIP_SAMPLE_V3).dataset.id
        val first = runs.start(datasetId, config("0.001"), RunStorage.DATASET_REFERENCE).run
        val second = runs.start(datasetId, config("0.002"), RunStorage.DATASET_REFERENCE).run
        var loads = 0
        val counting = object : ReplayDatasetCatalog by catalog {
            override fun load(id: String) = catalog.load(id).also { loads++ }
        }
        val countingPages = RunSearchIndexBackfillTransaction(runRepository, counting, snapshotReader, indexWriter, Clock.systemUTC())
        val template = TransactionTemplate(transactionManager)
        val cache = ReferenceIndexCache()
        strip(first.id)
        val firstPage = template.execute { countingPages.indexPage("", cache) }
        strip(second.id)
        val secondPage = template.execute { countingPages.indexPage("", cache) }

        assertEquals(listOf(1, 1), listOf(checkNotNull(firstPage).indexed, checkNotNull(secondPage).indexed))
        assertEquals(1, loads, "the second page took the dataset from the cache")
        assertEquals(listOf(ANCHOR, ANCHOR), listOf(first.id, second.id).map { checkNotNull(runs.find(it)).anchorBlockTime })
    }

    @Test
    fun `the cache keeps only the datasets used last`() {
        val cache = ReferenceIndexCache(capacity = 2)
        val loaded = mutableListOf<String>()
        fun index(id: String) = cache.indexOf(id) { loaded += id; null }

        listOf("a", "b", "a", "c", "a", "b").forEach(::index)

        assertEquals(listOf("a", "b", "c", "b"), loaded, "b was dropped for c, a stayed because it was used again")
    }

    @Test
    fun `more than one page of runs are all indexed`() {
        val fixtures = RunSearchFixtures(runRepository, tokenRepository, datasetRepository)
        val total = RunSearchIndexBackfillTransaction.PAGE_SIZE * 2 + 50
        (0 until total).forEach { fixtures.run("run-%04d".format(it), Instant.parse("2026-11-10T00:00:00Z").plusSeconds(it.toLong()), datasetId = "gone") }
        update("update simulation_runs set search_indexed_at = null")

        backfill.backfill()

        assertEquals(0L, count("select count(*) from simulation_runs where search_indexed_at is null"))
        assertEquals(total.toLong(), count("select count(*) from simulation_runs where search_indexed_at is not null"))
    }

    private fun expected(datasetId: String): RunSearchIndex = RunSearchIndex.of(catalog.load(datasetId))

    private fun config(fee: String) = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1, BigDecimal(fee))

    private fun strip(runId: String) {
        update("delete from simulation_run_tokens where run_id = '$runId'")
        update("update simulation_runs set anchor_block_time = null, search_indexed_at = null where id = '$runId'")
    }

    private fun indexedAt(runId: String): Instant? = runRepository.findOneById(runId)?.searchIndexedAt

    private fun update(sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.executeUpdate(sql) } }
    }

    private fun count(sql: String): Long = dataSource.connection.use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery(sql).use { rows -> rows.next(); rows.getLong(1) } }
    }

    private companion object {
        val ANCHOR: Instant = Instant.parse("2026-10-01T00:00:00Z")
        const val UNPARSABLE_INSTANT =
            """{"transactions":[{"wallet":{"value":"w"},"slot":{"value":1},"signature":{"value":"s"},"instructionIndex":1,"blockTime":"not-an-instant"}]}"""
        const val OVERFLOWING_SLOT = """{"transactions":[{"wallet":{"value":"w"},"slot":{"value":1e30}}]}"""
    }
}
