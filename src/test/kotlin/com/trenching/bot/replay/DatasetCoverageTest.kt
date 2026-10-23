package com.trenching.bot.replay

import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.papertrading.application.FillModel
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.CoverageShortfall
import com.trenching.bot.replay.domain.DatasetCoverage
import com.trenching.bot.replay.domain.MarketCoverage
import com.trenching.bot.replay.domain.ScenarioDecision
import com.trenching.bot.replay.domain.ScenarioResult
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
import com.trenching.bot.support.DipSampleV5
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class DatasetCoverageTest {
    private val config = SimulationConfig.mvp()
    private val sample = DipSample.load()
    private val report = ReplayRunner().run(sample, config)
    private val source = OrderedMarketData(sample.observations)

    /** Post-trade curve of `fixture:TP:1003:0`, the take-profit cycle's fill trade. */
    private val template = sample.observations.single { it.id == "fixture:TP:1003:0" }

    private fun decidedAt(cycle: Int): Slot =
        ScenarioDecision.decidedAt(report.scenarios.single { it.intent.pair == DipSample.pair(cycle) }, source, config)

    @Test
    fun `a closed position is decided at its close`() {
        assertEquals(Slot.of(1006), decidedAt(DipSample.TAKE_PROFIT))
        assertEquals(Slot.of(2005), decidedAt(DipSample.STOP_LOSS))
    }

    @Test
    fun `outcomes that need the whole window are decided at the horizon end`() {
        assertEquals(Slot.of(7500), decidedAt(DipSample.NO_DIP))
        assertEquals(Slot.of(11500), decidedAt(DipSample.HORIZON))
        assertEquals(Slot.of(12500), decidedAt(DipSample.NO_EXIT_FILL))
        assertEquals(Slot.of(13500), decidedAt(DipSample.NO_FILL))
    }

    @Test
    fun `a migration is decided at the first completed curve`() {
        assertEquals(Slot.of(5001), decidedAt(DipSample.MIGRATED_EARLY))
        assertEquals(Slot.of(6003), decidedAt(DipSample.MIGRATED_IN_POSITION))
    }

    @Test
    fun `an unsupported supply is decided at the buy without market data`() {
        assertEquals(Slot.of(9500), decidedAt(DipSample.UNSUPPORTED_SUPPLY))
    }

    @Test
    fun `a buy below the minimum market cap is decided at the buy`() {
        assertEquals(Slot.of(4000), decidedAt(DipSample.LOW_CAP))
    }

    @Test
    fun `a fill rejected after the trigger because the curve completed is decided at the completion`() {
        val observations = listOf(observation(1003, curve = COMPLETE, curveComplete = true), observation(1005))
        val scenario = filled(observations, config(latencySlots = 3))

        assertEquals(RejectionReason.NO_FILL_AFTER_TRIGGER, assertIs<FillOutcome.Rejected>(scenario.outcome).reason)
        assertEquals(Slot.of(1003), ScenarioDecision.decidedAt(scenario, OrderedMarketData(observations), config(latencySlots = 3)))
    }

    @Test
    fun `an entry that exceeds the curve is decided at its fill trade, skipping ineligible ones`() {
        val observations = listOf(observation(1003).copy(quality = ObservationQuality.INVALID), observation(1004, curve = TEN_THOUSAND_DOLLARS),
            observation(1005, curve = ROOMY))
        val config = config(notional = "100")
        val scenario = filled(observations, config)

        assertEquals(RejectionReason.EXCEEDS_CURVE, assertIs<FillOutcome.Rejected>(scenario.outcome).reason)
        assertEquals(Slot.of(1004), ScenarioDecision.decidedAt(scenario, OrderedMarketData(observations), config))
    }

    @Test
    fun `an entry too small for one raw token unit is decided at its fill trade`() {
        val observations = listOf(observation(1003, curve = EXPENSIVE), observation(1004))
        val scenario = filled(observations, config)

        assertEquals(RejectionReason.ZERO_QUANTITY, assertIs<FillOutcome.Rejected>(scenario.outcome).reason)
        assertEquals(Slot.of(1003), ScenarioDecision.decidedAt(scenario, OrderedMarketData(observations), config))
    }

    @Test
    fun `v3 and v4 datasets record no coverage, so no scenario is ever undecided`() {
        assertEquals(emptyList(), DatasetCoverage.undecided(report, sample))
        val v4 = DipSampleV4.dataset()
        assertEquals(emptyList(), DatasetCoverage.undecided(ReplayRunner().run(v4, config), v4))
    }

    @Test
    fun `a v5 dataset covering every full window decides every scenario`() {
        val v5 = DipSampleV5.dataset()

        assertEquals(emptyList(), DatasetCoverage.undecided(ReplayRunner().run(v5, config), v5))
    }

    @Test
    fun `a scenario decided one slot after its pair's coverage is undecided`() {
        val takeProfit = DipSample.pair(DipSample.TAKE_PROFIT)
        val atClose = DipSampleV5.dataset(mapOf(takeProfit to 1006))
        val beforeClose = DipSampleV5.dataset(mapOf(takeProfit to 1005))

        assertEquals(emptyList(), DatasetCoverage.undecided(ReplayRunner().run(atClose, config), atClose))
        val shortfall = DatasetCoverage.undecided(ReplayRunner().run(beforeClose, config), beforeClose).single()
        assertEquals(takeProfit, shortfall.pair)
        assertEquals(Slot.of(1006), shortfall.decidedAt)
        assertEquals(Slot.of(1005), shortfall.throughSlot)
    }

    @Test
    fun `a decided capture refuses a configuration whose outcome lies beyond it`() {
        val takeProfit = DipSample.pair(DipSample.TAKE_PROFIT)
        val decided = DipSampleV5.dataset(mapOf(takeProfit to 1006))
        val wideTakeProfit = config(takeProfitBps = 5000)

        val shortfall = DatasetCoverage.undecided(ReplayRunner().run(decided, wideTakeProfit), decided).single()

        assertEquals(takeProfit, shortfall.pair)
        assertEquals(Slot.of(5500), shortfall.decidedAt)
    }

    @Test
    fun `a horizon longer than the coverage is undecided instead of reading the gap as no dip`() {
        val noDip = DipSample.pair(DipSample.NO_DIP)
        val short = DipSampleV5.dataset(mapOf(noDip to 5000))

        assertEquals(listOf(Slot.of(7500)), DatasetCoverage.undecided(ReplayRunner().run(short, config), short).map(CoverageShortfall::decidedAt))
    }

    @Test
    fun `a pair without a coverage entry has observed nothing, so only an unsupported supply is decided`() {
        val full = DipSampleV5.dataset()
        val unsupported = DipSample.pair(DipSample.UNSUPPORTED_SUPPLY)
        val noDip = DipSample.pair(DipSample.NO_DIP)
        val dataset = full.copy(coverage = full.coverage.filterNot { it.pair == unsupported || it.pair == noDip },
            observations = full.observations.filterNot { it.pair == unsupported || it.pair == noDip })

        val shortfalls = DatasetCoverage.undecided(ReplayRunner().run(dataset, config), dataset)

        assertEquals(listOf(noDip), shortfalls.map(CoverageShortfall::pair))
        assertEquals(null, shortfalls.single().throughSlot)
    }

    @Test
    fun `coverage starting after the buy's next slot cannot decide a scenario`() {
        val pair = DipSample.pair(DipSample.TAKE_PROFIT)
        val full = DipSampleV5.dataset()
        val late = full.copy(
            transactions = full.transactions.filter { it.pair == pair },
            observations = full.observations.filter { it.pair == pair && it.slot.value >= 1004 },
            coverage = listOf(MarketCoverage.of(pair, Slot.of(1004), Slot.of(5500))),
        )
        val lateReport = ReplayRunner().run(late, config)

        val shortfall = DatasetCoverage.undecided(lateReport, late).single()

        assertEquals(pair, shortfall.pair)
        assertEquals(Slot.of(5500), shortfall.decidedAt)
        assertEquals(null, shortfall.throughSlot)
    }

    @Test
    fun `coverage beginning before the buy's next slot still covers its outcome`() {
        val full = DipSampleV5.dataset()
        val earlier = full.copy(coverage = full.coverage.map {
            MarketCoverage.of(it.pair, Slot.of(0), it.throughSlot)
        })

        assertEquals(emptyList(), DatasetCoverage.undecided(ReplayRunner().run(earlier, config), earlier))
    }

    @Test
    fun `a lead coverage recorded through the buy's slot decides only the scenarios decided at their buy`() {
        val full = DipSampleV5.dataset()
        val buySlots = full.transactions.groupBy { it.pair }.mapValues { (_, buys) -> buys.minOf { it.slot.value } }
        val throughBuy = full.copy(coverage = full.coverage.map {
            MarketCoverage.of(it.pair, Slot.of(buySlots.getValue(it.pair) - 50), Slot.of(buySlots.getValue(it.pair)))
        })

        val shortfalls = DatasetCoverage.undecided(ReplayRunner().run(throughBuy, config), throughBuy)

        assertEquals(buySlots.keys - DipSample.pair(DipSample.UNSUPPORTED_SUPPLY) - DipSample.pair(DipSample.LOW_CAP), shortfalls.map(CoverageShortfall::pair).toSet())
        assertEquals(shortfalls.associate { it.pair to Slot.of(buySlots.getValue(it.pair)) }, shortfalls.associate { it.pair to it.throughSlot })
    }

    /** The take-profit cycle's intent (trigger at slot 1002, horizon end 5500), filled against [observations] instead. */
    private fun filled(observations: List<MarketObservation>, config: SimulationConfig): ScenarioResult {
        val intent = report.scenarios.single { it.intent.pair == DipSample.pair(DipSample.TAKE_PROFIT) }.intent.copy(notional = config.notional)
        return ScenarioResult(intent, FillModel().fill(intent, OrderedMarketData(observations), config), null, null)
    }

    private fun observation(slot: Long, curve: CurveState = template.curve, curveComplete: Boolean = false): MarketObservation =
        template.copy(id = "test:$slot", sourceEventId = "test:$slot", slot = Slot.of(slot), priceSlot = Slot.of(slot), curve = curve,
            curveComplete = curveComplete)

    private fun config(notional: String = "0.1", latencySlots: Long = 1, takeProfitBps: Int = 3000) =
        SimulationConfig.of(BigDecimal(notional), 2, 1500, takeProfitBps, 1500, BigDecimal("10000"), 4500, latencySlots, BigDecimal("0.001"))

    private companion object {
        val COMPLETE = curve(52_615_016_868, 611_802_521_716_526, 22_615_016_868, 0)
        val TEN_THOUSAND_DOLLARS = curve(40_000_000_000, 800_000_000_000_000, 10_000_000_000, 520_100_000_000_000)
        val ROOMY = curve(40_000_000_000, 800_000_000_000_000, 10_000_000_000, 800_000_000_000_000)
        val EXPENSIVE = curve(1_000_000_000_000_000_000, 1_000_000, 0, 1_000_000)

        fun curve(virtualSol: Long, virtualToken: Long, realSol: Long, realToken: Long) = CurveState(
            CurveReserves(BigInteger.valueOf(virtualSol), BigInteger.valueOf(virtualToken), BigInteger.valueOf(realSol),
                BigInteger.valueOf(realToken)), 95, 30)
    }
}
