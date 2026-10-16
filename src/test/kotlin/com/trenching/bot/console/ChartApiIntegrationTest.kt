package com.trenching.bot.console

import com.trenching.bot.console.ConsoleTestData.variant
import com.trenching.bot.support.AdditionalClockConfiguration
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** The chart endpoints over real HTTP and the real stores; needs Docker like every Testcontainers test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["trenching.console.enabled=true"])
@Tag("integration")
@ActiveProfiles("test")
@Import(PostgresTestDatabaseConfiguration::class, AdditionalClockConfiguration::class)
class ChartApiIntegrationTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var json: ObjectMapper

    private val http by lazy { ConsoleHttp(port, json) }
    private val pair = DipSample.pair(DipSample.TAKE_PROFIT).let { "${it.base.value}/${it.quote.value}" }

    @Test
    fun `a v3 run charts its prices and mcap with paper fills and no trader markers`() {
        val runId = startRun(variant(json, "chart-v3"), "chart-v3.json")

        val chart = http.get("/api/runs/$runId/chart?pair=$pair")

        assertEquals(200, chart.status, chart.raw)
        assertTrue(chart.body.path("valuationBasis").path("available").asBoolean())
        assertEquals("20000.000000000000", chart.body.path("candles").first().path("mcap").path("open").asString())
        assertFalse(chart.body.path("markers").path("available").asBoolean())
        assertEquals("NOT_RECORDED_BEFORE_SCHEMA_V4", chart.body.path("markers").path("unavailableReason").asString())
        assertEquals(listOf("ENTRY", "EXIT"), chart.body.path("paperFills").items().map { it.path("phase").asString() })
        assertTrue(chart.body.path("paperFills").items().all { it.path("kind").asString() == "PAPER" })
    }

    @Test
    fun `a run needs a pair when its dataset has several and a dataset chart has no paper fills`() {
        val content = variant(json, "chart-pairs")
        val datasetId = upload(content).path("dataset").path("id").asString()
        val runId = http.post("/api/runs", """{"datasetId":"$datasetId"}""").body.path("run").path("id").asString()

        assertEquals(400, http.get("/api/runs/$runId/chart").status)
        assertEquals(400, http.get("/api/datasets/$datasetId/chart").status)
        assertEquals("INVALID_REQUEST", http.get("/api/datasets/$datasetId/chart?pair=$pair&maxBuckets=0").body.path("code").asString())
        val dataset = http.get("/api/datasets/$datasetId/chart?pair=$pair")
        assertEquals(200, dataset.status, dataset.raw)
        assertEquals(0, dataset.body.path("paperFills").size())
    }

    @Test
    fun `a run whose dataset was deleted keeps its price candles but loses the mcap`() {
        val datasetId = upload(variant(json, "chart-deleted")).path("dataset").path("id").asString()
        val runId = http.post("/api/runs", """{"datasetId":"$datasetId"}""").body.path("run").path("id").asString()
        assertEquals(204, http.delete("/api/datasets/$datasetId").status)

        val chart = http.get("/api/runs/$runId/chart?pair=$pair")

        assertEquals(200, chart.status, chart.raw)
        assertEquals("RATES_UNAVAILABLE", chart.body.path("valuationBasis").path("unavailableReason").asString())
        assertTrue(chart.body.path("candles").size() > 0)
        assertTrue(chart.body.path("candles").items().all { it.path("mcap").isNull })
        assertTrue(chart.body.path("trackedWallet").isNull)
    }

    @Test
    fun `a v4 run lists its trade markers and its observations gain the trade`() {
        val datasetId = upload(DipSampleV4.json()).path("dataset").path("id").asString()
        val runId = http.post("/api/runs", """{"datasetId":"$datasetId"}""").body.path("run").path("id").asString()

        val chart = http.get("/api/runs/$runId/chart?pair=$pair")
        val observations = http.get("/api/runs/$runId/observations").body

        assertEquals(200, chart.status, chart.raw)
        assertTrue(chart.body.path("markers").path("available").asBoolean())
        assertEquals("ITEMS", chart.body.path("markers").path("mode").asString())
        assertTrue(chart.body.path("markers").path("items").items().all { it.path("trader").isString && it.path("tokenRaw").isString })
        assertTrue(observations.items().all { it.path("trade").path("trader").isString })
    }

    @Test
    fun `a windowed run chart still reports the pair's whole extent`() {
        val datasetId = upload(variant(json, "chart-extent")).path("dataset").path("id").asString()
        val runId = http.post("/api/runs", """{"datasetId":"$datasetId"}""").body.path("run").path("id").asString()
        val observations = http.get("/api/runs/$runId/observations").body.items()
            .filter { "${it.path("pair").path("base").asString()}/${it.path("pair").path("quote").asString()}" == pair }
        val first = observations.minOf { it.path("observedTime").asString() }
        val last = observations.maxOf { it.path("observedTime").asString() }

        val chart = http.get("/api/runs/$runId/chart?pair=$pair&from=$first&to=$first")

        assertEquals(200, chart.status, chart.raw)
        assertEquals(json.readTree("""{"firstTime":"$first","lastTime":"$last","firstSlot":${observations.minOf { it.path("slot").asLong() }},
            "lastSlot":${observations.maxOf { it.path("slot").asLong() }}}"""), chart.body.path("extent"))
    }

    private fun JsonNode.items(): List<JsonNode> = (this as Iterable<JsonNode>).toList()

    private fun upload(content: String): JsonNode {
        val reply = http.upload(content.toByteArray(), "chart.json")
        assertTrue(reply.status == 200 || reply.status == 201, "upload failed status=${reply.status} body=${reply.raw}")
        return reply.body
    }

    private fun startRun(content: String, fileName: String): String {
        val datasetId = http.upload(content.toByteArray(), fileName).body.path("dataset").path("id").asString()
        val started = http.post("/api/runs", """{"datasetId":"$datasetId"}""")
        assertEquals(201, started.status, started.raw)
        return started.body.path("run").path("id").asString()
    }
}
