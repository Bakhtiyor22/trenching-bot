package com.trenching.bot.replay

import com.trenching.bot.common.MintAddress
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.RunTotals
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import java.math.BigDecimal
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class RunTotalsTest {
    @Test
    fun `the dip sample sums its two closed cycles exactly`() {
        val totals = RunTotals.of(ReplayRunner().run(DipSample.load(), SimulationConfig.mvp()))
        assertEquals(RunTotals(MintAddress.WRAPPED_SOL, 10, 5, 5, 3, 2, BigDecimal(DipSample.TOTAL_PNL)), totals)
        assertEquals(DipSample.TOTAL_PNL, totals.realizedPnl.toPlainString())
    }

    @Test
    fun `without observations every cycle is rejected with zero pnl at model scale`() {
        val report = ReplayRunner().run(DipSample.load().copy(observations = emptyList(), referenceRates = emptyList()), SimulationConfig.mvp())
        val totals = RunTotals.of(report)
        assertEquals(listOf(10, 0, 10, 0, 0), listOf(totals.scenarioCount, totals.filledCount, totals.rejectedCount,
            totals.openCount, totals.closedCount))
        assertEquals("0.000000000000", totals.realizedPnl.toPlainString())
    }

    @Test
    fun `quote mint comes from the run configuration`() {
        val quote = MintAddress.of("11111111111111111111111111111113")
        val config = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1, BigDecimal("0.001"), quoteMint = quote)
        assertEquals(quote, RunTotals.of(ReplayRunner().run(DipSample.load(), config)).quoteMint)
    }
}
