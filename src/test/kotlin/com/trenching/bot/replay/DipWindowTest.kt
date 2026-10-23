package com.trenching.bot.replay

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.DatasetCoverage
import com.trenching.bot.replay.domain.ScenarioDecision
import com.trenching.bot.replay.domain.ScenarioResult
import com.trenching.bot.strategy.domain.DipWatchOutcome
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * Spec P7 on the sample's take-profit cycle: the wallet buys at slot 1000 (t0 = [T0]) with a dip level of 0.000000085, slot 1001
 * trades above it and slot 1002 below it, then slot 1003 is the fill trade.
 */
class DipWindowTest {
    private val sample = DipSample.load()
    private val pair = DipSample.pair(DipSample.TAKE_PROFIT)
    private val windowed = config(dipWindowMs = 30_000)

    @Test
    fun `a trigger at exactly t0 plus the window triggers, and the fill after the window still fills`() {
        val dataset = takeProfit(times = mapOf(1001L to 10, 1002L to 30, 1003L to 31, 1004L to 40, 1005L to 50, 1006L to 60))

        val scenario = run(dataset, windowed)

        assertEquals(DipWatchOutcome.TRIGGERED, scenario.intent.watch)
        assertEquals(Slot.of(1002), scenario.intent.trigger?.slot)
        assertEquals(Slot.of(1003), assertIs<FillOutcome.Filled>(scenario.outcome).fill.actualSlot)
    }

    @Test
    fun `a below-level trade one second after the window gives no dip, decided at that trade`() {
        val dataset = takeProfit(times = mapOf(1001L to 10, 1002L to 31, 1003L to 32))

        val scenario = run(dataset, windowed)

        assertNoDip(scenario)
        assertEquals(Slot.of(1002), decidedAt(scenario, dataset, windowed))
    }

    @Test
    fun `the watch stops at the first trade after the window even when a later one is lower`() {
        val dataset = takeProfit(times = mapOf(1001L to 10, 1002L to 31, 1003L to 32), prices = mapOf(1003L to "0.00000001"))

        val scenario = run(dataset, windowed)

        assertNoDip(scenario)
        assertEquals(Slot.of(1002), decidedAt(scenario, dataset, windowed))
    }

    @Test
    fun `a completed curve after the window is not a migration before entry`() {
        val dataset = takeProfit(times = mapOf(1001L to 31))
        val migrated = dataset.copy(observations = dataset.observations.map { if (it.slot.value == 1001L) it.copy(curveComplete = true) else it })

        val scenario = run(migrated, windowed)

        assertNoDip(scenario)
        assertEquals(Slot.of(1001), decidedAt(scenario, migrated, windowed))
    }

    @Test
    fun `a windowed watch with no trade after the window and no dip is decided at the horizon end`() {
        val noDipPair = DipSample.pair(DipSample.NO_DIP)
        val dataset = only(sample, noDipPair)

        val scenario = ReplayRunner().run(dataset, windowed).scenarios.single()

        assertEquals(RejectionReason.NO_DIP, assertIs<FillOutcome.Rejected>(scenario.outcome).reason)
        assertEquals(scenario.intent.horizonEndSlot, decidedAt(scenario, dataset, windowed))
    }

    @Test
    fun `a timed configuration cannot watch a buy without a block time, so it is no dip decided at the buy`() {
        val dataset = takeProfit(times = emptyMap()).let { it.copy(transactions = it.transactions.map { tx -> tx.copy(blockTime = null) }) }

        val scenario = run(dataset, windowed)

        assertNoDip(scenario)
        assertNull(scenario.intent.t0)
        assertEquals(Slot.of(1000), decidedAt(scenario, dataset, windowed))
        assertEquals(DipWatchOutcome.TRIGGERED, run(dataset, config()).intent.watch)
    }

    @Test
    fun `every intent carries the wallet buy's block time as t0, timed or not`() {
        val dataset = takeProfit(times = emptyMap())

        assertEquals(T0, run(dataset, windowed).intent.t0)
        assertEquals(T0, run(dataset, config()).intent.t0)
    }

    @Test
    fun `without a dip window the watch ignores trade times`() {
        val dataset = takeProfit(times = mapOf(1001L to 40, 1002L to 45, 1003L to 46))

        val scenario = run(dataset, config())

        assertEquals(DipWatchOutcome.TRIGGERED, scenario.intent.watch)
        assertEquals(Slot.of(1002), scenario.intent.trigger?.slot)
    }

    private fun assertNoDip(scenario: ScenarioResult) {
        assertEquals(DipWatchOutcome.NO_DIP, scenario.intent.watch)
        assertNull(scenario.intent.trigger)
        assertEquals(RejectionReason.NO_DIP, assertIs<FillOutcome.Rejected>(scenario.outcome).reason)
    }

    private fun run(dataset: ReplayDataset, config: SimulationConfig): ScenarioResult = ReplayRunner().run(dataset, config).scenarios.single()

    private fun decidedAt(scenario: ScenarioResult, dataset: ReplayDataset, config: SimulationConfig): Slot =
        ScenarioDecision.decidedAt(scenario, OrderedMarketData(dataset.observations), config,
            DatasetCoverage.recordedThrough(dataset)(scenario.intent))

    /** The take-profit cycle with each listed slot's trades moved to t0 plus that many seconds, and the listed prices. */
    private fun takeProfit(times: Map<Long, Long>, prices: Map<Long, String> = emptyMap()): ReplayDataset {
        val cycle = only(sample, pair)
        return cycle.copy(observations = cycle.observations.map { observation ->
            val slot = observation.slot.value
            observation.copy(
                observedTime = times[slot]?.let { T0.plusSeconds(it) } ?: observation.observedTime,
                price = prices[slot]?.let(::BigDecimal) ?: observation.price,
            )
        })
    }

    private fun only(dataset: ReplayDataset, pair: MarketPair): ReplayDataset =
        dataset.copy(transactions = dataset.transactions.filter { it.pair == pair }, observations = dataset.observations.filter { it.pair == pair })

    private fun config(dipWindowMs: Long? = null) =
        SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1, BigDecimal("0.001"), dipWindowMs = dipWindowMs)

    private companion object {
        val T0: Instant = Instant.parse("2026-10-01T00:00:00Z")
    }
}
