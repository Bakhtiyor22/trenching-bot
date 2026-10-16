package com.trenching.bot.console.api

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.PageResult
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.application.RunSetupService
import com.trenching.bot.console.mapper.ConfigResponseMapperImpl
import com.trenching.bot.console.mapper.ConsoleErrorMapperImpl
import com.trenching.bot.console.mapper.RecommendationResponseMapperImpl
import com.trenching.bot.console.mapper.RunPresetMapperImpl
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.SampleDataset
import java.io.InputStream
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class RunSetupControllerTest {
    private val json = JsonMapper.builder().build()
    private val catalog = FakeCatalog(mapOf(SINGLE to 1, DOUBLE to 2))
    private val presetMapper = RunPresetMapperImpl().also { ReflectionTestUtils.setField(it, "configResponseMapper", ConfigResponseMapperImpl()) }
    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(RunSetupController(RunSetupService(catalog), presetMapper, RecommendationResponseMapperImpl()))
        .setControllerAdvice(ConsoleExceptionHandler(ConsoleErrorMapperImpl()))
        .build()

    @Test
    fun `the preset names its source and engine and carries the agreed levels as basis points and percents`() {
        val body = body("/api/runs/preset", 200)

        assertEquals(listOf("id", "source", "engine", "config", "fields", "unsupportedRules"), body.propertyNames().toList())
        assertEquals("decu-dip-v2-on-dip-entry-v1", body["id"].asString())
        assertEquals("""{"document":"docs/strategy/decu-dip-v2-spec.md","strategy":"decu-dip","version":2,"updated":"2026-10-11"}""",
            body["source"].toString())
        assertEquals("dip-entry v1", body["engine"].asString())
        val config = body["config"]
        assertEquals(listOf(2000, 5500, 4000), listOf("dipBps", "takeProfitBps", "stopLossBps").map { config[it].asInt() })
        assertEquals(listOf("20", "55", "40"), listOf("dipPercent", "takeProfitPercent", "stopLossPercent").map { config[it].asString() })
        assertEquals("0.100000000000", config["notional"].asString())
        assertEquals("0.001000000000", config["transactionCostSol"].asString())
        assertEquals(4500, config["horizonSlots"].asLong())
        assertEquals(1, config["latencySlots"].asLong())
    }

    @Test
    fun `the preset flags the horizon as missing and lists the eight unsupported spec rules`() {
        val body = body("/api/runs/preset", 200)

        val horizon = body["fields"].single { it["field"].asString() == "horizonSlots" }
        assertEquals("""{"field":"horizonSlots","status":"MISSING","specRef":null,"noteCode":"NO_SINGLE_WINDOW_IN_SPEC"}""", horizon.toString())
        assertEquals(10, body["fields"].size())
        val rules = body["unsupportedRules"]
        assertEquals(8, rules.size())
        assertEquals("""{"specRef":"Section 8","code":"EXIT_RETRIES_AND_UNRESOLVED"}""", rules[6].toString())
    }

    @Test
    fun `a single-token dataset gets the agreed strategy, the legacy comparison and the single-token note in that order`() {
        val body = body("/api/runs/recommendations?datasetId=$SINGLE", 200)

        assertEquals(listOf("USE_AGREED_STRATEGY", "ADD_LEGACY_COMPARISON", "SINGLE_TOKEN_DATASET"), body.items().map { it["id"].asString() })
        assertTrue(body.items().all { it["appliesTo"].asString() == "RUN" })
        assertEquals("""{"id":"USE_AGREED_STRATEGY","appliesTo":"RUN","setting":{"field":"presetId","value":"decu-dip-v2-on-dip-entry-v1"},""" +
            """"facts":{"unsupportedRuleCount":8,"missingFieldCount":1},"caveatCode":null}""", body[0].toString())
        assertEquals("""{"dipPercent":"15","takeProfitPercent":"30","stopLossPercent":"15"}""", body[1]["facts"].toString())
        assertTrue(body[1]["setting"].isNull)
        assertEquals("""{"pairCount":1}""", body[2]["facts"].toString())
    }

    @Test
    fun `a dataset with several tokens gets no single-token note and the same answer every time`() {
        val first = body("/api/runs/recommendations?datasetId=$DOUBLE", 200)

        assertEquals(listOf("USE_AGREED_STRATEGY", "ADD_LEGACY_COMPARISON"), first.items().map { it["id"].asString() })
        assertEquals(first, body("/api/runs/recommendations?datasetId=$DOUBLE", 200))
    }

    @Test
    fun `recommendations for an unknown dataset are not found and a blank id is invalid`() {
        assertEquals("DATASET_NOT_FOUND", body("/api/runs/recommendations?datasetId=missing", 404)["code"].asString())
        val blank = body(get("/api/runs/recommendations").param("datasetId", " "), 400)
        assertEquals("INVALID_REQUEST", blank["code"].asString())
        assertEquals("is required", blank["fields"]["datasetId"].asString())
    }

    private fun JsonNode.items(): List<JsonNode> = (this as Iterable<JsonNode>).toList()

    private fun body(path: String, status: Int): JsonNode = body(get(path), status)

    private fun body(request: MockHttpServletRequestBuilder, status: Int): JsonNode {
        val response = mvc.perform(request).andReturn().response
        assertEquals(status, response.status, response.contentAsString)
        return json.readTree(response.contentAsString)
    }

    private class FakeCatalog(private val pairCounts: Map<String, Int>) : ReplayDatasetCatalog {
        override fun find(id: String): DatasetSummary? = pairCounts[id]?.let { count ->
            DatasetSummary(id, id, WalletAddress.of(WALLET), 3, 1, 1, null, null,
                List(count) { MarketPair.of(MintAddress.of(MINTS[it]), MintAddress.WRAPPED_SOL) }, Instant.EPOCH)
        }

        override fun register(name: String, json: InputStream): DatasetRegistration = error("unused")
        override fun importSample(sample: SampleDataset): DatasetRegistration = error("unused")
        override fun page(page: Int, size: Int): PageResult<DatasetSummary> = error("unused")
        override fun load(id: String): ReplayDataset = error("unused")
        override fun delete(id: String): Boolean = error("unused")
    }

    private companion object {
        const val SINGLE = "single"
        const val DOUBLE = "double"
        const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        val MINTS = listOf("7mupUtwu17wevEfnyrE6XsWa1X9WWntAPeqdckd2pump", "11111111111111111111111111111112")
    }
}
