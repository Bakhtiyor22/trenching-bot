package com.trenching.bot.replay

import com.trenching.bot.common.MintAddress
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.RunTotals
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class RunTotalsTest {
    private val loader = ReplayDatasetLoader(JsonMapper.builder().build())
    private fun dataset() = javaClass.getResourceAsStream("/replay/samples/weekly-buy-v1.json").use {
        loader.load(requireNotNull(it))
    }

    @Test
    fun `sample dataset sums three closed scenarios exactly`() {
        val totals = RunTotals.of(ReplayRunner().run(dataset(), SimulationConfig.mvp()))
        assertEquals(RunTotals(MintAddress.WRAPPED_SOL, 3, 3, 0, 0, 3, java.math.BigDecimal("26.089701492540")), totals)
        assertEquals("26.089701492540", totals.realizedPnl.toPlainString())
    }

    @Test
    fun `no entry observation rejects every scenario with zero pnl at model scale`() {
        val original = dataset()
        val report = ReplayRunner().run(original.copy(observations = original.observations.filter { it.slot.value >= 106 }),
            SimulationConfig.mvp())
        val totals = RunTotals.of(report)
        assertEquals(listOf(3, 0, 3, 0, 0), listOf(totals.scenarioCount, totals.filledCount, totals.rejectedCount,
            totals.openCount, totals.closedCount))
        assertEquals("0.000000000000", totals.realizedPnl.toPlainString())
    }

    @Test
    fun `missing exit leaves every position open with zero pnl`() {
        val original = dataset()
        val report = ReplayRunner().run(original.copy(observations = original.observations.filter { it.slot.value < 106 }),
            SimulationConfig.mvp())
        val totals = RunTotals.of(report)
        assertEquals(listOf(3, 3, 0, 3, 0), listOf(totals.scenarioCount, totals.filledCount, totals.rejectedCount,
            totals.openCount, totals.closedCount))
        assertEquals("0.000000000000", totals.realizedPnl.toPlainString())
    }

    @Test
    fun `quote mint comes from the run configuration`() {
        val quote = MintAddress.of("11111111111111111111111111111113")
        val config = SimulationConfig.of(java.math.BigDecimal("100"), 10, 50, 2, 2, 5, quoteMint = quote)
        assertEquals(quote, RunTotals.of(ReplayRunner().run(dataset(), config)).quoteMint)
    }
}
