package com.trenching.bot.replay

import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** Carrying trade fields must not change what a replay computes: schema v4 only adds data no strategy reads. */
class ReplayV3V4EquivalenceTest {
    private val config = SimulationConfig.mvp()
    private val v3 = ReplayRunner().run(DipSample.load(), config)
    private val v4 = ReplayRunner().run(ReplayDatasetLoader(JsonMapper.builder().build()).load(DipSampleV4.json().byteInputStream()), config)

    @Test
    fun `the v3 sample and its v4 twin have different datasets, so different run ids`() {
        assertNotEquals(v3.datasetChecksum, v4.datasetChecksum)
        assertNotEquals(v3.runId, v4.runId)
    }

    @Test
    fun `the engine and the configuration are the same`() {
        assertEquals(v3.engineVersion, v4.engineVersion)
        assertEquals(v3.configurationHash, v4.configurationHash)
        assertEquals(v3.configuration, v4.configuration)
    }

    @Test
    fun `ingestion is the same`() {
        assertEquals(v3.ingestion, v4.ingestion)
    }

    @Test
    fun `every scenario has the same economics apart from the ids that embed the dataset hash`() {
        assertEquals(v3.scenarios.size, v4.scenarios.size)
        v3.scenarios.indices.forEach { index ->
            assertEquals(ScenarioEconomics.of(v3, v3.scenarios[index]), ScenarioEconomics.of(v4, v4.scenarios[index]),
                "pair=${v3.scenarios[index].intent.pair.base.value}")
        }
        assertTrue(v3.scenarios.any { it.position?.close != null })
        assertTrue(v3.scenarios.any { it.outcome is FillOutcome.Rejected })
        assertTrue(v3.scenarios.any { it.exitUnavailable != null })
    }
}
