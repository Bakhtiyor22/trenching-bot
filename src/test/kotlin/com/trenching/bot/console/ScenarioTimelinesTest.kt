package com.trenching.bot.console

import com.trenching.bot.console.application.ScenarioTimelines
import com.trenching.bot.console.domain.ScenarioTimeline
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.ExitPriceAssumption
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.ScenarioResult
import com.trenching.bot.strategy.domain.DipWatchOutcome
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV6
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ScenarioTimelinesTest {
    private val sample = DipSample.load()
    private val report = ReplayRunner().run(sample, SimulationConfig.mvp())

    /** One second per slot from midnight, so every stage dated by its observation has its own instant. */
    private val observations: Map<String, MarketObservation> =
        sample.observations.map { it.copy(observedTime = MIDNIGHT.plusSeconds(it.slot.value)) }.associateBy { it.id }
    private val firstBuyAt = Instant.parse("2026-10-01T00:16:40Z")

    @Test
    fun `a triggered and closed scenario has each stage at its own observation's block time and the exit trigger at its own`() {
        val result = scenario(DipSample.TAKE_PROFIT)
        val fill = (result.outcome as FillOutcome.Filled).fill
        val triggerTime = assertNotNull(assertNotNull(result.position?.exitTrigger).time)

        val timeline = ScenarioTimelines.of(result.intent, observations[fill.observationId], result.position, firstBuyAt, observations)

        assertEquals(ScenarioTimeline(firstBuyAt, slotTime(1002), slotTime(1003), triggerTime, slotTime(1006)), timeline)
    }

    @Test
    fun `a scenario without a dip has only its first buy`() {
        val result = scenario(DipSample.NO_DIP)
        assertEquals(DipWatchOutcome.NO_DIP, result.intent.watch)

        val timeline = ScenarioTimelines.of(result.intent, null, result.position, firstBuyAt, observations)

        assertEquals(ScenarioTimeline(firstBuyAt, null, null, null, null), timeline)
    }

    @Test
    fun `a take profit sold against the standing curve is dated at its trigger, never before it`() {
        val noExitFill = DipSample.pair(DipSample.NO_EXIT_FILL)
        val covered = DipSampleV6.dataset(mapOf(noExitFill to Instant.parse("2026-10-01T00:02:01Z")))
        val result = ReplayRunner().run(covered, SimulationConfig.mvp()).scenarios.single { it.intent.pair == noExitFill }
        val position = assertNotNull(result.position)
        val close = assertNotNull(position.close)
        assertEquals(ExitPriceAssumption.STANDING_CURVE, close.priceAssumption)
        assertEquals("fixture:NOEXIT:8003:0", close.observationId)
        val trigger = assertNotNull(position.exitTrigger)
        val laterTrigger = position.copy(exitTrigger = trigger.copy(time = slotTime(8003).plusSeconds(30)))
        val untimedTrigger = position.copy(exitTrigger = trigger.copy(time = null))

        fun exitAt(held: Position) = ScenarioTimelines.of(result.intent, observations[held.fill.observationId], held, firstBuyAt, observations).exitAt

        assertEquals(slotTime(8003).plusSeconds(30), exitAt(laterTrigger))
        assertEquals(slotTime(8003), exitAt(untimedTrigger))
        assertEquals(slotTime(8003), exitAt(position))
    }

    @Test
    fun `a trigger stored before engine dip-paper-v4 has no time and is dated by its trade`() {
        val result = scenario(DipSample.TAKE_PROFIT)
        val position = assertNotNull(result.position)
        val untimed = position.copy(exitTrigger = assertNotNull(position.exitTrigger).copy(time = null))

        val timeline = ScenarioTimelines.of(result.intent, observations[position.fill.observationId], untimed, firstBuyAt, observations)

        assertEquals(slotTime(1005), timeline.exitTriggerAt)
    }

    @Test
    fun `a time exit is triggered and sold at its exit start, against the curve standing before it`() {
        val horizon = DipSample.pair(DipSample.HORIZON)
        val dataset = DipSampleV6.dataset(mapOf(horizon to Instant.parse("2026-10-01T00:04:00Z")), mapOf(horizon to 9000))
        val result = ReplayRunner().run(dataset, TIMED).scenarios.single { it.intent.pair == horizon }
        val position = assertNotNull(result.position)
        val exitStart = assertNotNull(position.exitStart)
        val byId = dataset.observations.associateBy { it.id }
        assertEquals(CloseReason.TIME_EXIT, assertNotNull(position.close).reason)
        assertTrue(byId.getValue(assertNotNull(position.close).observationId).observedTime < exitStart)

        val timeline = ScenarioTimelines.of(result.intent, byId[position.fill.observationId], position, firstBuyAt, byId)

        assertEquals(exitStart, timeline.exitTriggerAt)
        assertEquals(exitStart, timeline.exitAt)
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
        val TIMED: SimulationConfig = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1,
            BigDecimal("0.001"), maxHoldMs = 180_000, hardExitStartMs = 240_000)
    }
}
