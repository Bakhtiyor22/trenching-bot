package com.trenching.bot.console

import com.trenching.bot.console.ConsoleTestData.UNKNOWN_ID
import com.trenching.bot.console.ConsoleTestData.sampleText
import com.trenching.bot.console.ConsoleTestData.variant
import com.trenching.bot.console.application.ConsoleMetrics
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import java.math.BigDecimal
import com.trenching.bot.support.AdditionalClockConfiguration
import io.micrometer.core.instrument.MeterRegistry
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Each test uses its own dataset variant; only the first test touches the bundled sample. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "trenching.console.enabled=true",
        "spring.servlet.multipart.max-file-size=256KB",
        "spring.servlet.multipart.max-request-size=256KB",
    ],
)
@Tag("integration")
@ActiveProfiles("test")
@Import(PostgresTestDatabaseConfiguration::class, AdditionalClockConfiguration::class)
class ConsoleApiIntegrationTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var meterRegistry: MeterRegistry

    private val http by lazy { ConsoleHttp(port, json) }

    @Test
    fun `the sample imports once and its default run is created once`() {
        val imported = http.post("/api/datasets/samples/dip-sample-v3")
        val reimported = http.post("/api/datasets/samples/DIP_SAMPLE_V3")
        val datasetId = imported.body.path("dataset").path("id").asString()

        assertEquals(201, imported.status)
        assertTrue(imported.body.path("created").asBoolean())
        assertEquals(200, reimported.status)
        assertFalse(reimported.body.path("created").asBoolean())
        assertEquals(datasetId, reimported.body.path("dataset").path("id").asString())
        assertEquals(1, count("select count(*) from replay_datasets where id = ?", datasetId))

        val started = http.post("/api/runs", """{"datasetId":"$datasetId"}""")
        val run = started.body.path("run")
        assertEquals(201, started.status)
        assertTrue(started.body.path("created").asBoolean())
        assertDecimal(DipSample.TOTAL_PNL, run.path("realizedPnl"))
        assertEquals(5, run.path("filledCount").asInt())
        assertEquals(2, run.path("closedCount").asInt())
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T[\d:.]+Z""").matches(run.path("createdAt").asString()), run.toString())
        assertFalse(run.path("createdAt").asString().startsWith("1900-"))

        val restarted = http.post("/api/runs", """{"datasetId":"$datasetId","config":{"notional":"0.1","latencySlots":1}}""")
        assertEquals(200, restarted.status)
        assertFalse(restarted.body.path("created").asBoolean())
        assertEquals(run.path("id").asString(), restarted.body.path("run").path("id").asString())
        assertEquals(5, count("select count(*) from positions where run_id = ?", run.path("id").asString()))
        assertTrue(counter(ConsoleMetrics.RUNS, ConsoleMetrics.CREATED) >= 1)
        assertTrue(counter(ConsoleMetrics.RUNS, ConsoleMetrics.EXISTING) >= 1)
        assertTrue((meterRegistry.find(ConsoleMetrics.RUN_DURATION).timer()?.count() ?: 0) >= 2)
    }

    @Test
    fun `an out-of-range latency and the removed fee field are per-field config errors and write nothing`() {
        val datasetId = upload(variant(json, "latency"), "latency.json").path("dataset").path("id").asString()
        val runsBefore = count("select count(*) from simulation_runs")

        val rejected = http.post("/api/runs", """{"datasetId":"$datasetId","config":{"latencySlots":151,"horizonSlots":4501,"feeBps":10}}""")

        assertEquals(400, rejected.status)
        assertEquals("CONFIG_INVALID", rejected.body.path("code").asString())
        assertEquals(setOf("latencySlots", "horizonSlots", "feeBps"), rejected.body.path("fields").propertyNames().toSet())
        assertEquals("is not a configuration field", rejected.body.path("fields").path("feeBps").asString())
        assertEquals(400, http.post("/api/runs", """{"datasetId":"$datasetId","config":{"latencySlots":0}}""").status)
        assertEquals(runsBefore, count("select count(*) from simulation_runs"))
    }

    @Test
    fun `a notional sent as a JSON number is rejected`() {
        val datasetId = upload(variant(json, "number"), "number.json").path("dataset").path("id").asString()

        val rejected = http.post("/api/runs", """{"datasetId":"$datasetId","config":{"notional":100}}""")

        assertEquals(400, rejected.status)
        assertEquals("CONFIG_INVALID", rejected.body.path("code").asString())
        assertTrue(rejected.body.path("fields").path("notional").asString().isNotBlank())
    }

    @Test
    fun `a run request without a dataset id is invalid`() {
        val rejected = http.post("/api/runs", "{}")

        assertEquals(400, rejected.status)
        assertEquals("INVALID_REQUEST", rejected.body.path("code").asString())
        assertTrue(rejected.body.path("fields").has("datasetId"))
    }

    @Test
    fun `a tampered checksum is rejected with the loader's message and stores nothing`() {
        val original = sampleText()
        val checksum = json.readTree(original).path("checksum").asString()
        val tampered = original.replace(checksum, "f".repeat(64))
        val datasetsBefore = count("select count(*) from replay_datasets")
        val invalidBefore = counter(ConsoleMetrics.DATASET_UPLOADS, ConsoleMetrics.INVALID)

        val rejected = http.upload(tampered.toByteArray(), "tampered.json")

        assertEquals(400, rejected.status)
        assertEquals("DATASET_INVALID", rejected.body.path("code").asString())
        assertTrue(rejected.body.path("message").asString().contains("Replay checksum mismatch"))
        assertEquals(datasetsBefore, count("select count(*) from replay_datasets"))
        assertEquals(invalidBefore + 1, counter(ConsoleMetrics.DATASET_UPLOADS, ConsoleMetrics.INVALID))
    }

    @Test
    fun `an upload is created once and a re-upload under another name returns the existing dataset`() {
        val content = variant(json, "reupload")

        val first = http.upload(content.toByteArray(), "first.json", "Reupload label")
        val second = http.upload(content.toByteArray(), "second.json")

        assertEquals(201, first.status)
        assertEquals(200, second.status)
        assertFalse(second.body.path("created").asBoolean())
        assertEquals(first.body.path("dataset").path("id").asString(), second.body.path("dataset").path("id").asString())
        assertEquals("Reupload label", second.body.path("dataset").path("name").asString())
    }

    @Test
    fun `an upload without a name is labelled with its file name`() {
        val uploaded = upload(variant(json, "unnamed"), "unnamed-dataset.json")

        assertEquals("unnamed-dataset.json", uploaded.path("dataset").path("name").asString())
    }

    @Test
    fun `empty and non-JSON uploads are invalid datasets`() {
        val empty = http.upload(ByteArray(0), "empty.json")
        val text = http.upload("not json".toByteArray(), "text.json")

        assertEquals(400, empty.status)
        assertEquals("DATASET_INVALID", empty.body.path("code").asString())
        assertEquals(400, text.status)
        assertEquals("DATASET_INVALID", text.body.path("code").asString())
    }

    @Test
    fun `UTF16 and UTF32 BOM uploads and malformed UTF8 are rejected without writes`() {
        val before = count("select count(*) from replay_datasets")
        val content = variant(json, "encoding")
        val encodings = listOf(
            byteArrayOf(0xff.toByte(), 0xfe.toByte()) + content.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xfe.toByte(), 0xff.toByte()) + content.toByteArray(Charsets.UTF_16BE),
            byteArrayOf(0, 0, 0xfe.toByte(), 0xff.toByte()) + content.toByteArray(charset("UTF-32BE")),
            byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0) + content.toByteArray(charset("UTF-32LE")),
            content.replace("variant-encoding", "variant-encoding-invalid").toByteArray() + byteArrayOf(0xc3.toByte(), 0x28),
        )

        encodings.forEach { bytes ->
            assertError(400, "DATASET_INVALID", http.upload(bytes, "encoding.json"))
            assertEquals(before, count("select count(*) from replay_datasets"))
        }
    }

    @Test
    fun `an upload over the size limit is rejected with 413`() {
        val rejected = http.upload(ByteArray(300 * 1024) { ' '.code.toByte() }, "large.json")

        assertEquals(413, rejected.status)
        assertEquals("UPLOAD_TOO_LARGE", rejected.body.path("code").asString())
    }

    @Test
    fun `unknown datasets, samples and runs are not found`() {
        assertError(404, "DATASET_NOT_FOUND", http.get("/api/datasets/$UNKNOWN_ID"))
        assertError(404, "DATASET_NOT_FOUND", http.get("/api/datasets/$UNKNOWN_ID/observations"))
        assertError(404, "SAMPLE_NOT_FOUND", http.post("/api/datasets/samples/no-such-sample"))
        assertError(404, "DATASET_NOT_FOUND", http.post("/api/runs", """{"datasetId":"$UNKNOWN_ID"}"""))
        listOf("", "/events", "/observations", "/orders", "/positions", "/report").forEach { suffix ->
            assertError(404, "RUN_NOT_FOUND", http.get("/api/runs/$UNKNOWN_ID$suffix"))
        }
    }

    @Test
    fun `run detail shows each scenario with its fill, selected observation and close as exact strings`() {
        val runId = startRun(variant(json, "detail"), "detail.json")

        val detail = http.get("/api/runs/$runId")

        assertEquals(200, detail.status)
        assertEquals(runId, detail.body.path("run").path("id").asString())
        assertDecimal("0.100000000000", detail.body.path("config").path("notional"))
        assertEquals(1, detail.body.path("config").path("latencySlots").asLong())
        assertDecimal("0.001000000000", detail.body.path("config").path("transactionCostSol"))
        assertTrue(detail.body.path("config").path("feeBps").isMissingNode)
        val scenarios = detail.body.path("scenarios")
        assertEquals(10, scenarios.size())
        assertTrue(scenarios.all { it.path("entryOffset").asInt() == 0 })
        val takeProfit = scenario(scenarios, DipSample.TAKE_PROFIT)
        assertEquals("FILLED", takeProfit.path("status").asString())
        assertEquals(1000, takeProfit.path("sourceSlot").asLong())
        assertEquals("TRIGGERED", takeProfit.path("watch").asString())
        assertSameDecimal("0.0000001", takeProfit.path("referencePrice"))
        assertSameDecimal("0.000000085", takeProfit.path("dipPrice"))
        assertEquals(1002, takeProfit.path("trigger").path("slot").asLong())
        assertDecimal("16800.000000000000", takeProfit.path("trigger").path("marketCapUsd"))
        assertSameDecimal("200", takeProfit.path("trigger").path("rate"))
        assertEquals(1003, takeProfit.path("selectedObservation").path("slot").asLong())
        assertTrue(takeProfit.path("rejectionReason").isNull)
        val fill = takeProfit.path("fill")
        assertDecimal("0.102250000000", fill.path("costBasis"))
        assertDecimal("0.001250000000", fill.path("fee"))
        assertEquals(125, fill.path("feeBps").asInt())
        assertEquals(0, fill.path("slippageBps").asInt())
        assertEquals(19, fill.path("priceImpactBps").asInt())
        assertDecimal("0.001000000000", fill.path("priorityFee"))
        assertEquals("CLOSED", takeProfit.path("position").path("state").asString())
        assertSameDecimal("0.00000011201248686606967374718216755569", takeProfit.path("position").path("takeProfitPrice"))
        assertSameDecimal("0.00000007323893372012247898854218647872", takeProfit.path("position").path("stopLossPrice"))
        assertEquals("TAKE_PROFIT", takeProfit.path("position").path("exitTrigger").path("reason").asString())
        assertEquals(1005, takeProfit.path("position").path("exitTrigger").path("slot").asLong())
        val close = takeProfit.path("position").path("close")
        assertDecimal(DipSample.TAKE_PROFIT_PNL, close.path("realizedPnl"))
        assertDecimal("0.001664690000", close.path("fee"))
        assertEquals(125, close.path("feeBps").asInt())
        assertDecimal("0.001000000000", close.path("transactionCost"))
        assertEquals(-22, close.path("priceImpactBps").asInt())
        assertTrue(takeProfit.path("exitUnavailableReason").isNull)
        val noDip = scenario(scenarios, DipSample.NO_DIP)
        assertEquals("REJECTED", noDip.path("status").asString())
        assertEquals("NO_DIP", noDip.path("rejectionReason").asString())
        assertTrue(noDip.path("trigger").isNull)
    }

    @Test
    fun `run tables list every stored record and the report downloads as an attachment`() {
        val runId = startRun(variant(json, "tables"), "tables.json")

        val events = http.get("/api/runs/$runId/events").body
        val observations = http.get("/api/runs/$runId/observations").body
        val orders = http.get("/api/runs/$runId/orders").body
        val positions = http.get("/api/runs/$runId/positions").body
        val report = http.get("/api/runs/$runId/report")

        assertEquals(11, events.size())
        assertTrue(events.all { it.path("baseDelta").isString })
        assertEquals("ACCEPTED", events.first().path("outcome").asString())
        assertEquals("BUY", events.first().path("side").asString())
        assertEquals(31, observations.size())
        assertTrue(observations.all { it.path("price").isString })
        assertEquals(10, orders.size())
        assertEquals(5, orders.count { it.path("status").asString() == "FILLED" && it.path("fill").path("executionPrice").isString })
        assertEquals(5, positions.size())
        assertEquals(2, positions.count { it.path("close").path("netProceeds").isString })
        assertTrue(positions.all { it.path("takeProfitPrice").isString && it.path("stopLossPrice").isString })
        assertEquals(200, report.status)
        assertTrue(requireNotNull(report.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment"))
        assertEquals(runId, report.body.path("runId").asString())
    }

    @Test
    fun `an open position reports why its exit is unavailable`() {
        // Up to three slots after each buy: every entry fills, and no exit can follow.
        val runId = startRun(variant(json, "open") { it.slot.value % 1000 <= 3 }, "open.json")
        dataSource.connection.use { connection ->
            connection.prepareStatement("update simulation_runs set report_snapshot = replace(report_snapshot, 'HORIZON_REACHED', 'NO_EXIT_FILL') where id = ?").use { statement ->
                statement.setString(1, runId)
                assertEquals(1, statement.executeUpdate())
            }
        }

        val scenarios = http.get("/api/runs/$runId").body.path("scenarios")
        val positions = http.get("/api/runs/$runId/positions").body

        val opened = scenarios.items().filter { !it.path("position").isNull }
        assertEquals(5, opened.size)
        opened.forEach { scenario ->
            assertEquals("OPEN", scenario.path("position").path("state").asString())
            assertTrue(scenario.path("position").path("close").isNull)
        }
        assertEquals("HORIZON_REACHED", scenario(scenarios, DipSample.TAKE_PROFIT).path("exitUnavailableReason").asString())
        assertEquals(3, positions.count { it.path("exitUnavailableReason").asString() == "HORIZON_REACHED" })
    }

    @Test
    fun `a rejected scenario carries its reason and no fill or position`() {
        val runId = startRun(variant(json, "rejected") { false }, "rejected.json")

        val scenarios = http.get("/api/runs/$runId").body.path("scenarios")
        val orders = http.get("/api/runs/$runId/orders").body

        assertEquals(10, scenarios.size())
        scenarios.forEach { scenario ->
            assertEquals("REJECTED", scenario.path("status").asString())
            assertTrue(scenario.path("rejectionReason").asString().isNotBlank())
            assertTrue(scenario.path("fill").isNull)
            assertTrue(scenario.path("position").isNull)
        }
        assertTrue(orders.all { it.path("status").asString() == "REJECTED" && it.path("fill").isNull })
    }

    @Test
    fun `datasets list newest first and observations filter by pair`() {
        val dataset = upload(variant(json, "listing"), "listing.json").path("dataset")
        val id = dataset.path("id").asString()
        val pair = dataset.path("pairs").first()
        val pairPath = pair.path("base").asString() + "/" + pair.path("quote").asString()

        val page = http.get("/api/datasets?page=0&size=1").body
        val found = http.get("/api/datasets/$id").body
        val filtered = http.get("/api/datasets/$id/observations?pair=$pairPath").body
        val all = http.get("/api/datasets/$id/observations").body

        assertEquals(1, page.path("items").size())
        assertEquals(1, page.path("size").asInt())
        assertTrue(page.path("total").asLong() >= 1)
        assertEquals(id, page.path("items").first().path("id").asString())
        assertEquals(31, found.path("observationCount").asInt())
        assertEquals(1000, found.path("firstSlot").asLong())
        assertEquals(31, all.size())
        assertEquals(8, filtered.size())
        assertTrue(filtered.all { it.path("pair") == pair })
        assertTrue(filtered.all { it.path("price").isString })
        assertEquals(filtered.items().map { it.path("slot").asLong() }.sorted(), filtered.items().map { it.path("slot").asLong() })
    }

    @Test
    fun `bad paging and pair parameters are invalid requests`() {
        val id = upload(variant(json, "params"), "params.json").path("dataset").path("id").asString()

        assertError(400, "INVALID_REQUEST", http.get("/api/datasets?page=-1"))
        assertError(400, "INVALID_REQUEST", http.get("/api/runs?size=0"))
        assertError(400, "INVALID_REQUEST", http.get("/api/runs?page=abc"))
        val badPair = http.get("/api/datasets/$id/observations?pair=not-a-pair")
        assertError(400, "INVALID_REQUEST", badPair)
        assertTrue(badPair.body.path("fields").has("pair"))
    }

    @Test
    fun `runs page by dataset`() {
        val content = variant(json, "run-list")
        val runId = startRun(content, "run-list.json")
        val datasetId = upload(content, "run-list.json").path("dataset").path("id").asString()

        val page = http.get("/api/runs?datasetId=$datasetId").body

        assertEquals(1, page.path("total").asLong())
        assertEquals(runId, page.path("items").first().path("id").asString())
        assertDecimal(DipSample.TOTAL_PNL, page.path("items").first().path("realizedPnl"))
    }

    @Test
    fun `deleting a dataset removes it from the catalog and keeps its runs`() {
        val content = variant(json, "delete")
        val runId = startRun(content, "delete.json")
        val datasetId = upload(content, "delete.json").path("dataset").path("id").asString()

        val deleted = http.delete("/api/datasets/$datasetId")

        assertEquals(204, deleted.status, deleted.raw)
        assertError(404, "DATASET_NOT_FOUND", http.get("/api/datasets/$datasetId"))
        assertError(404, "DATASET_NOT_FOUND", http.delete("/api/datasets/$datasetId"))
        assertEquals(runId, http.get("/api/runs?datasetId=$datasetId").body.path("items").first().path("id").asString())
        assertEquals(200, http.get("/api/runs/$runId").status)
    }

    @Test
    fun `config defaults are the mvp values with their allowed ranges`() {
        val defaults = http.get("/api/config/defaults").body

        assertDecimal("0.100000000000", defaults.path("defaults").path("notional"))
        assertEquals(1, defaults.path("defaults").path("latencySlots").asLong())
        assertDecimal("0.001000000000", defaults.path("defaults").path("transactionCostSol"))
        assertTrue(defaults.path("defaults").path("feeBps").isMissingNode)
        assertTrue(defaults.path("limits").path("slippageBps").isMissingNode)
        assertEquals(listOf(1500, 3000, 1500), listOf("dipBps", "takeProfitBps", "stopLossBps").map { defaults.path("defaults").path(it).asInt() })
        assertDecimal("10000.000000000000", defaults.path("defaults").path("minMarketCapUsd"))
        assertEquals(4500, defaults.path("defaults").path("horizonSlots").asLong())
        assertEquals("dip-entry", defaults.path("defaults").path("strategyName").asString())
        assertEquals(listOf(1L, 150L), listOf("min", "max").map { defaults.path("limits").path("latencySlots").path(it).asLong() })
        assertEquals(listOf("0", "0.1"), listOf("min", "max").map { defaults.path("limits").path("transactionCostSol").path(it).asString() })
        assertEquals(12, defaults.path("limits").path("transactionCostScale").asInt())
        assertEquals(1, defaults.path("limits").path("dipBps").path("min").asLong())
        assertEquals(4500, defaults.path("limits").path("horizonSlots").path("max").asLong())
        assertEquals(1000000000, defaults.path("limits").path("minMarketCapUsd").path("max").asLong())
        assertEquals(9, defaults.path("limits").path("notionalScale").asInt())
        assertEquals(35, defaults.path("limits").path("notionalPrecision").asInt())
    }

    @Test
    fun `the dashboard reports health, runs and rejection counts`() {
        startRun(variant(json, "dashboard"), "dashboard.json")

        val dashboard = http.get("/api/dashboard")

        assertEquals(200, dashboard.status)
        assertEquals("UP", dashboard.body.path("health").path("status").asString())
        assertEquals("UP", dashboard.body.path("health").path("database").asString())
        assertTrue(dashboard.body.path("runs").path("total").asLong() >= 1)
        assertTrue(dashboard.body.path("runs").path("recent").all { it.path("realizedPnl").isString })
        assertTrue(dashboard.body.path("runs").path("last24h").asLong() >= 1)
        listOf("scenarios" to listOf("filled", "rejected"), "positions" to listOf("open", "closed")).forEach { (group, fields) ->
            fields.forEach { assertTrue(dashboard.body.path(group).path(it).isNumber, "$group.$it") }
        }
        assertTrue(dashboard.body.path("scenarios").path("filled").asLong() >= 5)
        val pnl = dashboard.body.path("realizedPnlByQuoteMint").items()
        assertTrue(pnl.isNotEmpty())
        assertTrue(pnl.all { it.path("quoteMint").isString && it.path("realizedPnl").isString }, pnl.toString())
        assertEquals(pnl.map { it.path("quoteMint").asString() }.sorted(), pnl.map { it.path("quoteMint").asString() })
        assertEquals(RejectionReason.ENTRY.size, dashboard.body.path("rejectionsByReason").size())
        assertTrue(dashboard.body.path("gauges").path("heapUsedBytes").asLong() > 0)
    }

    private fun upload(content: String, fileName: String): JsonNode {
        val reply = http.upload(content.toByteArray(), fileName)
        assertTrue(reply.status == 200 || reply.status == 201, "upload failed status=${reply.status} body=${reply.raw}")
        return reply.body
    }

    private fun startRun(content: String, fileName: String): String {
        val datasetId = upload(content, fileName).path("dataset").path("id").asString()
        val started = http.post("/api/runs", """{"datasetId":"$datasetId"}""")
        assertEquals(201, started.status, started.raw)
        return started.body.path("run").path("id").asString()
    }

    private fun JsonNode.items(): List<JsonNode> = (this as Iterable<JsonNode>).toList()

    private fun scenario(scenarios: JsonNode, cycle: Int): JsonNode =
        scenarios.items().single { it.path("pair").path("base").asString() == DipSample.mint(cycle).value }

    /** Exact as a number; the JSON keeps the stored scale, which these checks do not pin. */
    private fun assertSameDecimal(expected: String, node: JsonNode) {
        assertTrue(node.isString, "expected a JSON string but was $node")
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(node.asString())), node.asString())
    }

    private fun assertDecimal(expected: String, node: JsonNode) {
        assertTrue(node.isString, "expected a JSON string but was $node")
        assertEquals(expected, node.asString())
    }

    private fun assertError(status: Int, code: String, reply: ConsoleReply) {
        assertEquals(status, reply.status, reply.raw)
        assertEquals(code, reply.body.path("code").asString(), reply.raw)
        assertTrue(reply.body.path("message").asString().isNotBlank(), reply.raw)
        assertTrue(reply.body.path("fields").isObject, reply.raw)
    }

    private fun counter(name: String, outcome: String): Double =
        meterRegistry.find(name).tag(ConsoleMetrics.OUTCOME, outcome).counter()?.count() ?: 0.0

    private fun count(sql: String, vararg parameters: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }
}
