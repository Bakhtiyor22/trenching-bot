package com.trenching.bot.replay

import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSampleV4
import com.trenching.bot.support.DipSampleV5
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** Coverage is read by the run gate, never by the engine: a v5 dataset covering every full window replays as its v4 form. */
class ReplayV4V5EquivalenceTest {
    private val config = SimulationConfig.mvp()
    private val v4 = ReplayRunner().run(DipSampleV4.dataset(), config)
    private val v5Dataset: ReplayDataset = ReplayDatasetLoader(JsonMapper.builder().build()).load(DipSampleV5.json().byteInputStream())
    private val v5 = ReplayRunner().run(v5Dataset, config)

    @Test
    fun `the v5 form is another dataset with the same engine and configuration`() {
        assertEquals(ReplayDataset.SCHEMA_V5, v5Dataset.schemaVersion)
        assertNotEquals(v4.datasetChecksum, v5.datasetChecksum)
        assertNotEquals(v4.runId, v5.runId)
        assertEquals(v4.engineVersion, v5.engineVersion)
        assertEquals(v4.configurationHash, v5.configurationHash)
    }

    @Test
    fun `ingestion and every scenario's outcome and pnl are the same`() {
        assertEquals(v4.ingestion, v5.ingestion)
        assertEquals(v4.scenarios.size, v5.scenarios.size)
        v4.scenarios.indices.forEach { index ->
            assertEquals(ScenarioEconomics.of(v4, v4.scenarios[index]), ScenarioEconomics.of(v5, v5.scenarios[index]),
                "pair=${v4.scenarios[index].intent.pair.base.value}")
        }
    }
}
