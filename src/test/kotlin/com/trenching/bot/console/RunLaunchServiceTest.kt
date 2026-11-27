package com.trenching.bot.console

import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.api.dto.RunStartRequest
import com.trenching.bot.console.application.ConsoleConfigValidator
import com.trenching.bot.console.application.RunLaunchService
import com.trenching.bot.console.application.RunVariationLabels
import com.trenching.bot.support.FakeRunVariationLookup
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.domain.RunStart
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.strategy.domain.SimulationConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import com.trenching.bot.console.domain.RunOutcome
import com.trenching.bot.console.domain.RunOutcomeStage
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.PositionOutcomeCounts
import com.trenching.bot.support.StubOutcomeStores
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class RunLaunchServiceTest {
    private val lookups = mutableListOf<List<String>>()
    private val stores = StubOutcomeStores()
    private val runs = mock(SimulationRuns::class.java)
    private val catalog = mock(ReplayDatasetCatalog::class.java)
    private val service = RunLaunchService(runs, catalog, ConsoleConfigValidator(), RunVariationLabels(FakeRunVariationLookup { ids ->
        lookups += ids.toList()
        mapOf("run-1" to listOf("V03", "V12"))
    }), stores.outcomes(), SimpleMeterRegistry())

    @Test
    fun `a started run comes back with its variations from one lookup of its own id`() {
        stubStart(summary("run-1"), created = true)

        val started = service.start(RunStartRequest(datasetId = "ds-1"))

        assertEquals(listOf("V03", "V12"), started.variations)
        assertEquals("run-1", started.run.id)
        assertEquals(true, started.created)
        assertEquals(listOf(listOf("run-1")), lookups)
    }

    @Test
    fun `a started run comes back with why its tokens were sold, held or not bought, from one read of each store`() {
        stores.rejections["run-1"] = mapOf(RejectionReason.NO_DIP to 1)
        stores.positionCounts["run-1"] = PositionOutcomeCounts(mapOf(CloseReason.STOP_LOSS to 2), emptyMap())
        stubStart(summary("run-1"), created = true)

        val started = service.start(RunStartRequest(datasetId = "ds-1"))

        assertEquals(listOf(RunOutcome("STOP_LOSS", RunOutcomeStage.SOLD, 2), RunOutcome("NO_DIP", RunOutcomeStage.NOT_BOUGHT, 1)), started.outcomes)
        assertEquals(listOf(listOf("run-1")), stores.orderCalls)
        assertEquals(listOf(listOf("run-1")), stores.positionCalls)
    }

    @Test
    fun `a run no variation produced has an empty list, and an existing run is looked up again`() {
        stubStart(summary("run-2"), created = false)

        val started = service.start(RunStartRequest(datasetId = "ds-1"))

        assertEquals(emptyList(), started.variations)
        assertEquals(false, started.created)
        assertEquals(listOf(listOf("run-2")), lookups)
    }

    private fun stubStart(run: RunSummary, created: Boolean) {
        `when`(catalog.find("ds-1")).thenReturn(mock(DatasetSummary::class.java))
        `when`(runs.start("ds-1", SimulationConfig.mvp())).thenReturn(RunStart(run, created))
    }

    private fun summary(id: String) = RunSummary(id, "ds-1", "engine", "config", "result", MintAddress.WRAPPED_SOL, 1, 1, 0, 0, 1,
        BigDecimal.ZERO, Instant.parse("2026-11-16T07:00:00Z"), RunStorage.COPY)
}
