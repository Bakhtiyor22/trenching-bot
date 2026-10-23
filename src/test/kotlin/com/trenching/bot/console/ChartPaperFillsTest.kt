package com.trenching.bot.console

import com.trenching.bot.console.application.ChartPaperFills
import com.trenching.bot.console.application.SlotClock
import com.trenching.bot.console.domain.PaperPhase
import com.trenching.bot.console.domain.RunScenario
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV6
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test

class ChartPaperFillsTest {
    @Test
    fun `a time exit's sale is placed at its exit start, not at the earlier trade whose curve it sold against`() {
        val horizon = DipSample.pair(DipSample.HORIZON)
        val dataset = DipSampleV6.dataset(mapOf(horizon to Instant.parse("2026-10-01T00:04:00Z")), mapOf(horizon to 9000))
        val result = ReplayRunner().run(dataset, TIMED).scenarios.single { it.intent.pair == horizon }
        val position = assertNotNull(result.position)
        val close = assertNotNull(position.close)
        assertEquals(CloseReason.TIME_EXIT, close.reason)
        val selected = dataset.observations.single { it.id == (result.outcome as FillOutcome.Filled).fill.observationId }
        val scenario = RunScenario(result.intent, result.outcome, selected, position, result.exitUnavailable)

        val fills = ChartPaperFills.of(listOf(scenario), dataset.observations, horizon, null, SlotClock.of(emptyList()))

        val exit = fills.single { it.phase == PaperPhase.EXIT }
        assertEquals(position.exitStart, exit.time)
        assertEquals(close.slot.value, exit.slot)
        assertEquals(CloseReason.TIME_EXIT.name, exit.reason)
    }

    @Test
    fun `a take profit sold against the standing curve is placed at its sale slot and its trigger's time`() {
        val noExitFill = DipSample.pair(DipSample.NO_EXIT_FILL)
        val dataset = DipSampleV6.dataset(mapOf(noExitFill to Instant.parse("2026-10-01T00:02:01Z")))
        val result = ReplayRunner().run(dataset, SimulationConfig.mvp()).scenarios.single { it.intent.pair == noExitFill }
        val position = assertNotNull(result.position)
        val trigger = assertNotNull(position.exitTrigger)
        val later = position.copy(exitTrigger = trigger.copy(time = assertNotNull(trigger.time).plusSeconds(1)))
        val selected = dataset.observations.single { it.id == (result.outcome as FillOutcome.Filled).fill.observationId }
        val scenario = RunScenario(result.intent, result.outcome, selected, later, result.exitUnavailable)

        val exit = ChartPaperFills.of(listOf(scenario), dataset.observations, noExitFill, null, SlotClock.of(emptyList()))
            .single { it.phase == PaperPhase.EXIT }

        assertEquals(assertNotNull(trigger.time).plusSeconds(1), exit.time)
        assertEquals(8004L, exit.slot)
        assertEquals(CloseReason.TAKE_PROFIT.name, exit.reason)
    }

    private companion object {
        val TIMED: SimulationConfig = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1,
            BigDecimal("0.001"), maxHoldMs = 180_000, hardExitStartMs = 240_000)
    }
}
