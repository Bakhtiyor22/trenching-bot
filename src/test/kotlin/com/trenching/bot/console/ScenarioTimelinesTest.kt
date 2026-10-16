package com.trenching.bot.console

import com.trenching.bot.console.application.ScenarioTimelines
import com.trenching.bot.console.domain.ScenarioTimeline
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ScenarioTimelinesTest {
    private val sample = DipSample.load()
    private val report = ReplayRunner().run(sample, SimulationConfig.mvp())
    private val observations = sample.observations.associateBy { it.id }

    @Test
    fun `dates every milestone of a closed scenario by the block times of the records it points at`() {
        val result = report.scenarios.first { it.position?.close != null }
        val position = assertNotNull(result.position)
        val fill = (result.outcome as FillOutcome.Filled).fill
        val buyAt = sample.observations.first().observedTime

        val timeline = ScenarioTimelines.of(result.intent, result.outcome, position, mapOf(result.intent.sourceEventId to buyAt), observations)

        assertEquals(ScenarioTimeline(
            firstBuyAt = buyAt,
            dipAt = result.intent.trigger?.let { observations.getValue(it.observationId).observedTime },
            entryAt = observations.getValue(fill.observationId).observedTime,
            exitTriggerAt = position.exitTrigger?.let { observations.getValue(it.observationId).observedTime },
            exitAt = observations.getValue(assertNotNull(position.close).observationId).observedTime,
        ), timeline)
        assertNotNull(timeline.exitAt)
    }

    @Test
    fun `leaves a milestone null when it did not happen or its record is missing`() {
        val result = report.scenarios.first { it.outcome is FillOutcome.Rejected }

        val timeline = ScenarioTimelines.of(result.intent, result.outcome, null, emptyMap(), emptyMap())

        assertEquals(ScenarioTimeline(null, null, null, null, null), timeline)
        assertNull(ScenarioTimelines.of(result.intent, result.outcome, null, mapOf(result.intent.sourceEventId to null), observations).firstBuyAt)
    }
}
