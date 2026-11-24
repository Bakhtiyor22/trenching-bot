package com.trenching.bot.console.api

import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.application.ConsoleService
import com.trenching.bot.console.application.RunLaunchService
import com.trenching.bot.console.application.RunSearchConsoleService
import com.trenching.bot.console.application.RunUsdtViewService
import com.trenching.bot.console.api.dto.RunStartRequest
import com.trenching.bot.console.domain.DatasetSource
import com.trenching.bot.console.domain.LabeledRunStart
import com.trenching.bot.console.domain.SourcedRunDetail
import com.trenching.bot.console.mapper.ConfigResponseMapperImpl
import com.trenching.bot.console.mapper.ConsoleErrorMapperImpl
import com.trenching.bot.console.mapper.DatasetSourceResponseMapperImpl
import com.trenching.bot.console.mapper.ObservationResponseMapperImpl
import com.trenching.bot.console.mapper.PairResponseMapperImpl
import com.trenching.bot.console.mapper.RunResponseMapperImpl
import com.trenching.bot.console.mapper.RunUsdtViewMapper
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class RunVariationsControllerTest {
    private val json = JsonMapper.builder().build()
    private val launch = mock(RunLaunchService::class.java)
    private val console = mock(ConsoleService::class.java)
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(RunController(
        launch, console, mock(RunSearchConsoleService::class.java), responseMapper(),
        ObservationResponseMapperImpl(), mock(RunUsdtViewService::class.java), mock(RunUsdtViewMapper::class.java),
    )).setControllerAdvice(ConsoleExceptionHandler(ConsoleErrorMapperImpl())).build()

    @Test
    fun `the run page carries the run's variations`() {
        `when`(console.detailWithSource("run-1")).thenReturn(detail(listOf("V03", "V12")))

        assertEquals(listOf("V03", "V12"), labels(body(get("/api/runs/run-1"))["run"]))
    }

    @Test
    fun `a run no variation produced has an empty list on its page, not a missing field`() {
        `when`(console.detailWithSource("run-1")).thenReturn(detail(emptyList()))

        val run = body(get("/api/runs/run-1"))["run"]

        assertEquals(emptyList(), labels(run))
        assertEquals(true, run["variations"].isArray)
    }

    @Test
    fun `a started run carries its variations, empty for a hand-started one`() {
        `when`(launch.start(RunStartRequest(datasetId = "ds-1"))).thenReturn(LabeledRunStart(summary(), true, listOf("V03")))
            .thenReturn(LabeledRunStart(summary(), false, emptyList()))

        val first = body(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""{"datasetId":"ds-1"}"""))
        val second = body(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""{"datasetId":"ds-1"}"""))

        assertEquals(listOf("V03"), labels(first["run"]))
        assertEquals(emptyList(), labels(second["run"]))
    }

    private fun body(request: org.springframework.test.web.servlet.RequestBuilder): JsonNode {
        val response = mvc.perform(request).andReturn().response
        assertEquals(true, response.status in 200..201, response.contentAsString)
        return json.readTree(response.contentAsString)
    }

    private fun labels(run: JsonNode): List<String> = run["variations"].iterator().asSequence().map { it.asString() }.toList()

    private fun summary() = RunSummary("run-1", "ds-1", "engine", "config", "result", MintAddress.WRAPPED_SOL, 1, 1, 0, 0, 1,
        BigDecimal.ZERO, Instant.parse("2026-11-16T07:00:00Z"), RunStorage.COPY)

    private fun detail(variations: List<String>) =
        SourcedRunDetail(summary(), SimulationConfig.mvp(), emptyList(), DatasetSource("ds-1", null, null), variations = variations)

    private fun responseMapper() = RunResponseMapperImpl().also {
        val pairs = PairResponseMapperImpl()
        ReflectionTestUtils.setField(it, "pairResponseMapper", pairs)
        ReflectionTestUtils.setField(it, "observationResponseMapper", ObservationResponseMapperImpl().also { observation ->
            ReflectionTestUtils.setField(observation, "pairResponseMapper", pairs)
        })
        ReflectionTestUtils.setField(it, "configResponseMapper", ConfigResponseMapperImpl())
        ReflectionTestUtils.setField(it, "datasetSourceResponseMapper", DatasetSourceResponseMapperImpl())
    }
}
