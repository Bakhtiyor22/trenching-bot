package com.trenching.bot.replay

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.replay.application.DatasetFingerprint
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.*
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import javax.sql.DataSource
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Import(PostgresTestDatabaseConfiguration::class)
class ReplayDatasetCatalogIntegrationTest {
    @Autowired private lateinit var catalog: ReplayDatasetCatalog
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var runs: SimulationRuns

    private fun sampleJson(): String = requireNotNull(javaClass.getResource(SAMPLE)).readText()
    private fun sample(): ReplayDataset = ReplayDatasetLoader(json).load(sampleJson().byteInputStream())
    private fun variant(tag: String): ReplayDataset = sample().let { dataset ->
        dataset.copy(referenceRates = dataset.referenceRates.map { it.copy(source = "variant-$tag") })
    }
    private fun write(dataset: ReplayDataset): String = ReplayDatasetWriter(json).write(dataset)

    private fun count(sql: String, vararg parameters: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
        }
    }

    @Test
    fun `importing the sample twice stores it once`() {
        val id = DatasetFingerprint.checksum(sample())
        dataSource.connection.use { connection ->
            connection.prepareStatement("delete from replay_datasets where id = ?").use { it.setString(1, id); it.executeUpdate() }
        }
        val first = catalog.importSample(SampleDataset.DIP_SAMPLE_V3)
        val second = catalog.importSample(SampleDataset.DIP_SAMPLE_V3)
        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(id, first.dataset.id)
        assertEquals(first.dataset, second.dataset)
        assertEquals("dip-sample-v3", second.dataset.name)
        assertEquals(1, count("select count(*) from replay_datasets where id = ?", id))
    }

    @Test
    fun `identical content under another name returns the existing dataset unchanged`() {
        val content = write(variant("rename"))
        val first = catalog.register("first", content.byteInputStream())
        val reformatted = json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(content))
        val second = catalog.register("second", reformatted.byteInputStream())
        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.dataset, second.dataset)
        assertEquals("first", second.dataset.name)
        assertEquals(1, count("select count(*) from replay_datasets where id = ?", first.dataset.id))
    }

    @Test
    fun `summary describes the deduplicated dataset`() {
        val dataset = variant("summary")
        val repeated = json.readTree(write(dataset)) as ObjectNode
        (repeated.get("transactions") as ArrayNode).let { it.addAll(it.deepCopy()) }
        val registered = catalog.register("summary", json.writeValueAsString(repeated).byteInputStream()).dataset
        val pairs = dataset.transactions.map { it.pair }.distinct().sortedWith(compareBy({ it.base.value }, { it.quote.value }))
        val expected = DatasetSummary(DatasetFingerprint.checksum(dataset), "summary", WalletAddress.of("11111111111111111111111111111111"),
            3, 11, 31, Slot.of(1000), Slot.of(11501), pairs, registered.createdAt)
        assertEquals(expected, registered)
        assertEquals(expected, catalog.find(registered.id))
    }

    @Test
    fun `load returns the stored dataset with the same checksum and simulation result`() {
        val original = variant("load")
        val id = catalog.register("load", write(original).byteInputStream()).dataset.id
        val loaded = catalog.load(id)
        assertEquals(id, DatasetFingerprint.checksum(loaded))
        assertEquals(ReplayRunner().run(original, SimulationConfig.mvp()), ReplayRunner().run(loaded, SimulationConfig.mvp()))
    }

    @Test
    fun `empty dataset has no slot range`() {
        val empty = variant("empty").copy(transactions = emptyList(), observations = emptyList())
        val registered = catalog.register("empty", write(empty).byteInputStream()).dataset
        assertNull(registered.firstSlot)
        assertNull(registered.lastSlot)
        assertEquals(emptyList(), registered.pairs)
        assertEquals(registered, catalog.find(registered.id))
        assertEquals(empty, catalog.load(registered.id))
    }

    @Test
    fun `invalid uploads keep the loader message and store nothing`() {
        val before = count("select count(*) from replay_datasets")
        val tampered = assertFailsWith<DatasetInvalidException> {
            catalog.register("tampered", sampleJson().replace("\"0.000000086\"", "\"0.000000087\"").byteInputStream())
        }
        assertTrue(requireNotNull(tampered.message).contains("Replay checksum mismatch"), tampered.message)
        assertFailsWith<DatasetInvalidException> { catalog.register("malformed", "{".byteInputStream()) }
        assertFailsWith<DatasetInvalidException> { catalog.register("empty", "".byteInputStream()) }
        assertFailsWith<DatasetInvalidException> { catalog.register(" ", write(variant("blank-name")).byteInputStream()) }
        assertEquals(before, count("select count(*) from replay_datasets"))
    }

    @Test
    fun `deleting a dataset keeps its runs and re-registering the same content restores its id`() {
        val dataset = variant("delete")
        val registered = catalog.register("to delete", write(dataset).byteInputStream()).dataset
        val run = runs.start(registered.id, SimulationConfig.mvp()).run

        assertTrue(catalog.delete(registered.id))

        assertNull(catalog.find(registered.id))
        assertFailsWith<DatasetNotFoundException> { catalog.load(registered.id) }
        assertEquals(0, count("select count(*) from replay_datasets where id = ?", registered.id))
        assertEquals(1, count("select count(*) from simulation_runs where id = ?", run.id))
        assertEquals(listOf(run.id), runs.page(0, 20, registered.id).items.map { it.id })
        assertTrue(runs.report(run.id).isNotEmpty())
        assertFalse(catalog.delete(registered.id))

        val restored = catalog.register("restored", write(dataset).byteInputStream())
        assertTrue(restored.created)
        assertEquals(registered.id, restored.dataset.id)
    }

    @Test
    fun `unknown dataset is not found`() {
        val id = "0".repeat(64)
        assertNull(catalog.find(id))
        assertFailsWith<DatasetNotFoundException> { catalog.load(id) }
    }

    @Test
    fun `pages list newest first with the size capped`() {
        catalog.register("page-a", write(variant("page-a")).byteInputStream())
        catalog.register("page-b", write(variant("page-b")).byteInputStream())
        val page = catalog.page(0, 1000)
        assertEquals(100, page.size)
        assertEquals(0, page.page)
        assertTrue(page.total >= 2)
        assertEquals(page.items.sortedWith(compareByDescending<DatasetSummary> { it.createdAt }.thenBy { it.id }), page.items)
        assertEquals(1, catalog.page(1, 1).items.size)
    }

    private companion object {
        const val SAMPLE = "/replay/samples/dip-sample-v3.json"
    }
}
