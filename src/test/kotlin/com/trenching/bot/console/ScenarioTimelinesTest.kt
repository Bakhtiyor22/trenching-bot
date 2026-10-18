package com.trenching.bot.console

import com.trenching.bot.console.application.ScenarioTimelines
import com.trenching.bot.console.domain.ScenarioTimeline
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.ScenarioResult
import com.trenching.bot.strategy.domain.DipWatchOutcome
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ScenarioTimelinesTest {
    private val sample = DipSample.load()
    private val report = ReplayRunner().run(sample, SimulationConfig.mvp())

    /** One second per slot from midnight, so every stage has its own instant. */
    private val observations: Map<String, MarketObservation> =
        sample.observations.map { it.copy(observedTime = MIDNIGHT.plusSeconds(it.slot.value)) }.associateBy { it.id }
    private val firstBuyAt = Instant.parse("2026-10-01T00:16:40Z")

    @Test
    fun `a triggered and closed scenario has each stage at its own observation's block time`() {
        val result = scenario(DipSample.TAKE_PROFIT)
        val fill = (result.outcome as FillOutcome.Filled).fill

        val timeline = ScenarioTimelines.of(result.intent, observations[fill.observationId], result.position, firstBuyAt, observations)

        assertEquals(ScenarioTimeline(firstBuyAt, slotTime(1002), slotTime(1003), slotTime(1005), slotTime(1006)), timeline)
    }

    @Test
    fun `a scenario without a dip has only its first buy`() {
        val result = scenario(DipSample.NO_DIP)
        assertEquals(DipWatchOutcome.NO_DIP, result.intent.watch)

        val timeline = ScenarioTimelines.of(result.intent, null, result.position, firstBuyAt, observations)

        assertEquals(ScenarioTimeline(firstBuyAt, null, null, null, null), timeline)
    }

    @Test
    fun `an exit triggered without a later fill has an exit trigger but no exit`() {
        val result = scenario(DipSample.NO_EXIT_FILL)
        val position = assertNotNull(result.position)
        val exitTrigger = assertNotNull(position.exitTrigger)
        assertNull(position.close)

        val timeline = ScenarioTimelines.of(result.intent, observations[position.fill.observationId], position, firstBuyAt, observations)

        assertEquals(slotTime(exitTrigger.slot.value), timeline.exitTriggerAt)
        assertNull(timeline.exitAt)
    }

    @Test
    fun `a first buy without a block time and a stage whose observation is not stored are left empty`() {
        val result = scenario(DipSample.TAKE_PROFIT)
        val fill = (result.outcome as FillOutcome.Filled).fill
        val withoutTrigger = observations - assertNotNull(result.intent.trigger).observationId

        val timeline = ScenarioTimelines.of(result.intent, observations[fill.observationId], result.position, null, withoutTrigger)

        assertNull(timeline.firstBuyAt)
        assertNull(timeline.dipAt)
        assertEquals(slotTime(1003), timeline.entryAt)
    }

    private fun scenario(cycle: Int): ScenarioResult = report.scenarios.single { it.intent.pair == DipSample.pair(cycle) }

    private fun slotTime(slot: Long): Instant = MIDNIGHT.plusSeconds(slot)

    private companion object {
        val MIDNIGHT: Instant = Instant.parse("2026-10-01T00:00:00Z")
    }
}
