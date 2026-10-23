package com.trenching.bot.replay

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.MarketCoverage
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.SimulationReport
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import com.trenching.bot.strategy.domain.DipWatchOutcome
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
import com.trenching.bot.support.DipSampleV5
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * A market window recorded from 50 slots before its first buy S adds trades replay never reads: the same capture exported
 * with and without its lead replays to the same outcomes. Every lead trade sits at the dip trigger's price, and slot S also
 * holds the tracked wallet's own buy, so reading any of them would trigger an entry.
 */
class LeadEquivalenceTest {
    private val config = SimulationConfig.mvp()
    private val mapper = JsonMapper.builder().build()
    private val withoutLead = DipSampleV5.dataset()
    private val withoutLeadReport = ReplayRunner().run(withoutLead, config)
    private val dipTrade = triggerOf(withoutLeadReport, DipSample.TAKE_PROFIT)
    private val withLead = roundTrip(lead(withoutLead) { it })
    private val withLeadReport = ReplayRunner().run(withLead, config)

    @Test
    fun `the lead form is another dataset whose coverage starts fifty slots before each first buy`() {
        assertEquals(firstBuys(withoutLead).map { (pair, slot) -> pair to slot - LEAD }.toMap(),
            withLead.coverage.associate { it.pair to it.fromSlot.value })
        assertEquals(withoutLead.coverage.associate { it.pair to it.throughSlot }, withLead.coverage.associate { it.pair to it.throughSlot })
        assertNotEquals(withoutLeadReport.datasetChecksum, withLeadReport.datasetChecksum)
        assertNotEquals(withoutLeadReport.runId, withLeadReport.runId)
        assertEquals(withoutLeadReport.configurationHash, withLeadReport.configurationHash)
    }

    @Test
    fun `ingestion and every scenario's outcome, trigger, fill, close and pnl are the same`() {
        assertEquals(withoutLeadReport.ingestion, withLeadReport.ingestion)
        assertEquals(withoutLeadReport.scenarios.size, withLeadReport.scenarios.size)
        withoutLeadReport.scenarios.indices.forEach { index ->
            assertEquals(ScenarioEconomics.of(withoutLeadReport, withoutLeadReport.scenarios[index]),
                ScenarioEconomics.of(withLeadReport, withLeadReport.scenarios[index]), "pair=${withoutLeadReport.scenarios[index].intent.pair.base.value}")
        }
    }

    @Test
    fun `a dip triggered two slots after the buy keeps its trigger slot`() {
        val buySlot = firstBuys(withoutLead).getValue(DipSample.pair(DipSample.TAKE_PROFIT))

        assertEquals(Slot.of(buySlot + 2), dipTrade.slot)
        assertEquals(dipTrade.slot, triggerOf(withLeadReport, DipSample.TAKE_PROFIT).slot)
    }

    @Test
    fun `the no-dip cycle stays undipped although its lead trades would trigger it once inside the window`() {
        val noDip = DipSample.pair(DipSample.NO_DIP)
        val buySlot = firstBuys(withoutLead).getValue(noDip)
        val readable = lead(withoutLead) { observation ->
            if (observation.pair == noDip) observation.copy(slot = Slot.of(buySlot + 1), priceSlot = Slot.of(buySlot + 1)) else observation
        }

        assertEquals(DipWatchOutcome.NO_DIP, scenario(withLeadReport, DipSample.NO_DIP).intent.watch)
        assertEquals(DipWatchOutcome.TRIGGERED, scenario(ReplayRunner().run(readable, config), DipSample.NO_DIP).intent.watch)
    }

    private fun scenario(report: SimulationReport, cycle: Int) = report.scenarios.single { it.intent.pair == DipSample.pair(cycle) }

    private fun triggerOf(report: SimulationReport, cycle: Int): MarketObservation {
        val trigger = checkNotNull(scenario(report, cycle).intent.trigger) { "cycle $cycle has no trigger" }
        return withoutLead.observations.single { it.id == trigger.observationId }
    }

    private fun firstBuys(dataset: ReplayDataset): Map<MarketPair, Long> =
        dataset.transactions.groupBy { it.pair }.mapValues { (_, buys) -> buys.minOf { it.slot.value } }

    /**
     * [dataset] as exported with the lead: each pair's coverage starts at `S - 50`, and the lead holds dip-priced trades at
     * `S - 50` and `S - 1` and, in slot S, one before the tracked wallet's own buy and one after it. [place] moves a lead trade.
     */
    private fun lead(dataset: ReplayDataset, place: (MarketObservation) -> MarketObservation): ReplayDataset {
        val firstBuys = firstBuys(dataset)
        val leadTrades = firstBuys.flatMap { (pair, buySlot) ->
            val minute = dataset.observations.filter { it.pair == pair }.minBy { it.slot.value }.observedTime
            listOf(buySlot - LEAD to OTHER, buySlot - 1 to OTHER, buySlot to OTHER, buySlot to dataset.trackedWallet.value, buySlot to OTHER)
                .mapIndexed { index, (slot, trader) ->
                    dipTrade.copy(id = "lead:${pair.base.value}:$index", pair = pair, slot = Slot.of(slot), priceSlot = Slot.of(slot),
                        observedTime = minute, sourceEventId = "lead:${pair.base.value}:$index",
                        trade = ObservedTrade.of(trader, TradeSide.BUY, BigInteger.valueOf(1_000_000L + index), BigInteger.valueOf(10_000_000_000L)))
                }
                .map(place)
        }
        return dataset.copy(observations = dataset.observations + leadTrades,
            coverage = dataset.coverage.map { MarketCoverage.of(it.pair, Slot.of(firstBuys.getValue(it.pair) - LEAD), it.throughSlot) })
    }

    private fun roundTrip(dataset: ReplayDataset): ReplayDataset =
        ReplayDatasetLoader(mapper).load(ReplayDatasetWriter(mapper).write(dataset).byteInputStream())

    private companion object {
        const val LEAD = 50L
        val OTHER: String = DipSampleV4.trade(1).trader
    }
}
