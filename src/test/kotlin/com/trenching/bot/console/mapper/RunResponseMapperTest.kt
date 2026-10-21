package com.trenching.bot.console.mapper

import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.application.ScenarioTimelines
import com.trenching.bot.console.domain.CaptureSource
import com.trenching.bot.console.domain.DatasetSource
import com.trenching.bot.console.domain.RunScenario
import com.trenching.bot.console.domain.SourcedRunDetail
import com.trenching.bot.console.domain.TimedRunScenario
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class RunResponseMapperTest {
    private val json = JsonMapper.builder().build()
    private val sample = DipSample.load()
    private val report = ReplayRunner().run(sample, SimulationConfig.mvp())
    private val observations = sample.observations.map { it.copy(observedTime = MIDNIGHT.plusSeconds(it.slot.value)) }.associateBy { it.id }
    private val mapper = RunResponseMapperImpl().also {
        val pairs = PairResponseMapperImpl()
        ReflectionTestUtils.setField(it, "pairResponseMapper", pairs)
        ReflectionTestUtils.setField(it, "observationResponseMapper", ObservationResponseMapperImpl().also { observation ->
            ReflectionTestUtils.setField(observation, "pairResponseMapper", pairs)
        })
        ReflectionTestUtils.setField(it, "configResponseMapper", ConfigResponseMapperImpl())
        ReflectionTestUtils.setField(it, "datasetSourceResponseMapper", DatasetSourceResponseMapperImpl())
    }

    @Test
    fun `run detail names its dataset, capture and case at the top level`() {
        val body = body(DatasetSource("dataset-1", "Decu case dataset", CaptureSource("s1", "Decu · 7mup…pump · 16 Oct 20:13", "case-1")))

        assertEquals("""{"datasetId":"dataset-1","datasetName":"Decu case dataset","captureId":"s1","captureDisplayName":"Decu · 7mup…pump · 16 Oct 20:13","caseId":"case-1"}""",
            body["source"].toString())
    }

    @Test
    fun `run detail carries the measured slot pace, or null without one`() {
        val measured = json.readTree(json.writeValueAsString(mapper.toResponse(detail(DatasetSource("dataset-1", null, null)).copy(millisPerSlot = 217.6))))

        assertEquals(217.6, measured["millisPerSlot"].asDouble())
        assertTrue(body(DatasetSource("dataset-1", null, null))["millisPerSlot"].isNull)
    }

    @Test
    fun `a run without a capture has null capture fields`() {
        val source = body(DatasetSource("dataset-1", null, null))["source"]

        assertEquals(listOf("datasetId", "datasetName", "captureId", "captureDisplayName", "caseId"), source.propertyNames().toList())
        assertTrue(listOf("datasetName", "captureId", "captureDisplayName", "caseId").all { source[it].isNull })
    }

    @Test
    fun `a triggered and closed scenario carries every instant of its timeline`() {
        val timeline = scenario(body(DatasetSource("dataset-1", null, null)), DipSample.TAKE_PROFIT)["timeline"]

        assertEquals(listOf("firstBuyAt", "dipAt", "entryAt", "exitTriggerAt", "exitAt"), timeline.propertyNames().toList())
        assertEquals(listOf("2026-10-01T00:16:40Z", "2026-10-01T00:16:42Z", "2026-10-01T00:16:43Z", "2026-10-01T00:16:45Z", "2026-10-01T00:16:46Z"),
            timeline.propertyNames().map { timeline[it].asString() })
    }

    @Test
    fun `a scenario without a dip carries only its first buy`() {
        val timeline = scenario(body(DatasetSource("dataset-1", null, null)), DipSample.NO_DIP)["timeline"]

        assertEquals("2026-10-01T00:50:00Z", timeline["firstBuyAt"].asString())
        assertTrue(listOf("dipAt", "entryAt", "exitTriggerAt", "exitAt").all { timeline[it].isNull })
    }

    private fun body(source: DatasetSource): JsonNode = json.readTree(json.writeValueAsString(mapper.toResponse(detail(source))))

    private fun scenario(body: JsonNode, cycle: Int): JsonNode =
        body["scenarios"].single { it["pair"]["base"].asString() == DipSample.mint(cycle).value }

    private fun detail(source: DatasetSource): SourcedRunDetail {
        val scenarios = report.scenarios.map { result ->
            val selected = (result.outcome as? FillOutcome.Filled)?.let { observations[it.fill.observationId] }
            val firstBuyAt = MIDNIGHT.plusSeconds(result.intent.creationSlot.value)
            TimedRunScenario(RunScenario(result.intent, result.outcome, selected, result.position, result.exitUnavailable),
                ScenarioTimelines.of(result.intent, selected, result.position, firstBuyAt, observations))
        }
        val run = RunSummary(report.runId, "dataset-1", report.engineVersion, report.configurationHash, report.resultChecksum,
            MintAddress.WRAPPED_SOL, 10, 5, 5, 3, 2, BigDecimal(DipSample.TOTAL_PNL), Instant.parse("2026-10-08T00:00:00Z"))
        return SourcedRunDetail(run, SimulationConfig.mvp(), scenarios, source)
    }

    private companion object {
        val MIDNIGHT: Instant = Instant.parse("2026-10-01T00:00:00Z")
    }
}
