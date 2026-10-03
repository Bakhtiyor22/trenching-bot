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

    private fun sampleJson(): String = requireNotNull(javaClass.getResource(SAMPLE)).readText()
    private fun sample(): ReplayDataset = ReplayDatasetLoader(json).load(sampleJson().byteInputStream())
    private fun variant(tag: String): ReplayDataset = sample().let { dataset ->
        dataset.copy(transactions = dataset.transactions.map { it.copy(source = "variant-$tag") })
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
        val first = catalog.importSample(SampleDataset.WEEKLY_BUY_V1)
        val second = catalog.importSample(SampleDataset.WEEKLY_BUY_V1)
        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(id, first.dataset.id)
        assertEquals(first.dataset, second.dataset)
        assertEquals("weekly-buy-v1", second.dataset.name)
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
        val pair = MarketPair.of(MintAddress.of("11111111111111111111111111111112"), MintAddress.WRAPPED_SOL)
        val expected = DatasetSummary(DatasetFingerprint.checksum(dataset), "summary", WalletAddress.of("11111111111111111111111111111111"),
            1, 4, 6, Slot.of(100), Slot.of(111), listOf(pair), registered.createdAt)
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
            catalog.register("tampered", sampleJson().replace("\"10\"", "\"12\"").byteInputStream())
        }
        assertTrue(requireNotNull(tampered.message).contains("Replay checksum mismatch"), tampered.message)
        assertFailsWith<DatasetInvalidException> { catalog.register("malformed", "{".byteInputStream()) }
        assertFailsWith<DatasetInvalidException> { catalog.register("empty", "".byteInputStream()) }
        assertFailsWith<DatasetInvalidException> { catalog.register(" ", write(variant("blank-name")).byteInputStream()) }
        assertEquals(before, count("select count(*) from replay_datasets"))
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
        const val SAMPLE = "/replay/samples/weekly-buy-v1.json"
    }
}
