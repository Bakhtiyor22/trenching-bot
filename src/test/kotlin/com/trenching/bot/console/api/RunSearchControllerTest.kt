package com.trenching.bot.console.api

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.PageResult
import com.trenching.bot.console.application.ConsoleService
import com.trenching.bot.console.application.RunLaunchService
import com.trenching.bot.console.application.RunSearchConsoleService
import com.trenching.bot.console.application.RunVariationLabels
import com.trenching.bot.console.application.RunUsdtViewService
import com.trenching.bot.console.mapper.ConfigResponseMapperImpl
import com.trenching.bot.console.mapper.ConsoleErrorMapperImpl
import com.trenching.bot.console.mapper.DatasetSourceResponseMapperImpl
import com.trenching.bot.console.mapper.ObservationResponseMapperImpl
import com.trenching.bot.console.mapper.PairResponseMapperImpl
import com.trenching.bot.console.mapper.RunResponseMapperImpl
import com.trenching.bot.console.mapper.RunUsdtViewMapper
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.domain.RunAggregates
import com.trenching.bot.replay.domain.RunSearchQuery
import com.trenching.bot.replay.domain.RunSearchResult
import com.trenching.bot.replay.domain.RunStart
import com.trenching.bot.replay.domain.RunStatus
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper

class RunSearchControllerTest {
    private val json = JsonMapper.builder().build()
    private val runs = FakeRuns()
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(RunController(
        mock(RunLaunchService::class.java), mock(ConsoleService::class.java),
        RunSearchConsoleService(runs, RunVariationLabels { mapOf("run-1" to listOf("V03", "V12")) }, Clock.fixed(NOW, ZoneOffset.UTC)), responseMapper(),
        ObservationResponseMapperImpl(), mock(RunUsdtViewService::class.java), mock(RunUsdtViewMapper::class.java),
    )).setControllerAdvice(ConsoleExceptionHandler(ConsoleErrorMapperImpl())).build()

    @Test
    fun `the page carries its snapshot, every status count and the new run fields`() {
        val response = mvc.perform(get("/api/runs").param("page", "1").param("size", "5")).andReturn().response

        assertEquals(200, response.status, response.contentAsString)
        val body = json.readTree(response.contentAsString)
        assertEquals(NOW.minusSeconds(5).toString(), body["asOf"].asString(), "the snapshot is the settled time, five seconds before now")
        assertEquals(listOf("PROFIT", "LOSS", "FLAT", "NO_ENTRY", "OPEN"), body["statusCounts"].propertyNames().toList())
        assertEquals(1, body["statusCounts"]["LOSS"].asInt())
        assertEquals(1, body["total"].asInt())
        val item = body["items"][0]
        assertEquals("LOSS", item["status"].asString())
        assertEquals("Alpha", item["datasetName"].asString())
        assertEquals(listOf(MINT), item["mints"].iterator().asSequence().map { it.asString() }.toList())
        assertEquals(ANCHOR.toString(), item["anchorBlockTime"].asString())
        assertEquals(listOf("V03", "V12"), item["variations"].iterator().asSequence().map { it.asString() }.toList())
        assertEquals(1, checkNotNull(runs.query).page)
    }

    @Test
    fun `every filter is parsed into the query`() {
        val response = mvc.perform(get("/api/runs")
            .param("status", "LOSS").param("status", "OPEN").param("mint", MINT).param("q", " alpha ")
            .param("anchorFrom", "2026-11-10T07:00:00Z").param("anchorTo", "2026-11-11T07:00:00Z")
            .param("createdFrom", "2026-11-01T00:00:00Z").param("createdTo", "2026-11-12T00:00:00Z")
            .param("asOf", "2026-11-15T10:00:00.5Z").param("datasetId", "ds-1")).andReturn().response

        assertEquals(200, response.status, response.contentAsString)
        val query = checkNotNull(runs.query)
        assertEquals(setOf(RunStatus.LOSS, RunStatus.OPEN), query.statuses)
        assertEquals(MintAddress.of(MINT), query.mint)
        assertEquals("alpha", query.text)
        assertEquals(Instant.parse("2026-11-10T07:00:00Z"), query.anchorFrom)
        assertEquals(Instant.parse("2026-11-11T07:00:00Z"), query.anchorTo)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), query.createdFrom)
        assertEquals(Instant.parse("2026-11-12T00:00:00Z"), query.createdTo)
        assertEquals(Instant.parse("2026-11-15T10:00:00.5Z"), query.asOf)
        assertEquals("ds-1", query.datasetId)
        assertEquals(Instant.parse("2026-11-15T10:00:00.5Z"), Instant.parse(json.readTree(response.contentAsString)["asOf"].asString()))
    }

    @Test
    fun `an invalid parameter is a 400 INVALID_REQUEST naming the field`() {
        mapOf(
            "status" to "WON", "mint" to "nope", "q" to "a", "anchorFrom" to "yesterday",
        ).forEach { (name, value) ->
            val response = mvc.perform(get("/api/runs").param(name, value)).andReturn().response

            assertEquals(400, response.status, name)
            val body = json.readTree(response.contentAsString)
            assertEquals("INVALID_REQUEST", body["code"].asString(), name)
            assertEquals(listOf(name), body["fields"].propertyNames().toList(), name)
        }
    }

    @Test
    fun `a range whose start is not before its end is a 400 naming its end`() {
        val response = mvc.perform(get("/api/runs").param("createdFrom", "2026-11-12T00:00:00Z").param("createdTo", "2026-11-12T00:00:00Z"))
            .andReturn().response

        assertEquals(400, response.status)
        assertEquals(listOf("createdTo"), json.readTree(response.contentAsString)["fields"].propertyNames().toList())
    }

    private fun responseMapper() = RunResponseMapperImpl().also {
        val pairs = PairResponseMapperImpl()
        ReflectionTestUtils.setField(it, "pairResponseMapper", pairs)
        ReflectionTestUtils.setField(it, "observationResponseMapper", ObservationResponseMapperImpl().also { observation ->
            ReflectionTestUtils.setField(observation, "pairResponseMapper", pairs)
        })
        ReflectionTestUtils.setField(it, "configResponseMapper", ConfigResponseMapperImpl())
        ReflectionTestUtils.setField(it, "datasetSourceResponseMapper", DatasetSourceResponseMapperImpl())
    }

    private class FakeRuns : SimulationRuns {
        var query: RunSearchQuery? = null

        override fun search(query: RunSearchQuery): RunSearchResult {
            this.query = query
            val run = RunSummary("run-1", "ds-1", "engine", "config", "result", MintAddress.WRAPPED_SOL, 2, 2, 0, 0, 2,
                BigDecimal("-1.000000000000"), NOW, RunStorage.COPY, "Alpha", listOf(MintAddress.of(MINT)), ANCHOR)
            val counts = RunStatus.entries.associateWith { if (it == RunStatus.LOSS) 1L else 0L }
            return RunSearchResult(PageResult(listOf(run), query.page, query.size, 1), counts)
        }

        override fun start(datasetId: String, config: SimulationConfig, storage: RunStorage): RunStart = error("not used")
        override fun page(page: Int, size: Int, datasetId: String?): PageResult<RunSummary> = error("not used")
        override fun find(runId: String): RunSummary? = error("not used")
        override fun report(runId: String): String = error("not used")
        override fun totals(createdSince: Instant): RunAggregates = error("not used")
    }

    private companion object {
        const val MINT = "11111111111111111111111111111112"
        val NOW: Instant = Instant.parse("2026-11-16T07:00:00Z")
        val ANCHOR: Instant = Instant.parse("2026-11-10T07:00:00Z")
    }
}
