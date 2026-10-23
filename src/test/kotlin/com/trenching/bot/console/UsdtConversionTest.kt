package com.trenching.bot.console

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.application.UsdtConversion
import com.trenching.bot.console.domain.RunDetail
import com.trenching.bot.console.domain.RunScenario
import com.trenching.bot.console.domain.RunUsdtView
import com.trenching.bot.console.domain.UsdtScenario
import com.trenching.bot.marketdata.RateLookup
import com.trenching.bot.marketdata.RateUnavailableReason
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.replay.domain.SimulationReport
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * The dip sample converted with hand-checked display rates: 200 USDT per SOL in its first minute (slots below 5000, so
 * both closed cycles), 150 in the second and 151 in the third. Amounts are at scale 12, prices at scale 38, HALF_EVEN.
 */
class UsdtConversionTest {
    private val dataset: ReplayDataset = DipSample.load()
    private val report: SimulationReport = ReplayRunner().run(dataset, SimulationConfig.mvp())
    private var lookups = 0
    private val requested = mutableSetOf<Instant>()
    private var extra = emptyList<MarketObservation>()

    @Test
    fun `converts every price and leg at its own minute and sums the closed positions exactly`() {
        val view = convert(RATES)

        assertNull(view.unavailableReason)
        assertEquals("binance:SOLUSDT:1m:open", view.rateSource)
        val fill = assertNotNull(scenario(view, DipSample.TAKE_PROFIT).fill)
        assertEquals(BigDecimal("0.00001723269028708764211495110270087600"), fill.executionPriceUsdt)
        assertEquals(BigDecimal("20.450000000000"), fill.costBasisUsdt)
        assertEquals(BigDecimal("0.250000000000"), fill.feeUsdt)
        assertEquals(BigDecimal("0.200000000000"), fill.priorityFeeUsdt)
        assertEquals(MINUTE, fill.minute)
        assertEquals(BigDecimal("200"), fill.rate)
        val close = assertNotNull(scenario(view, DipSample.TAKE_PROFIT).close)
        assertEquals(BigDecimal("0.332938000000"), close.feeUsdt)
        assertEquals(BigDecimal("0.200000000000"), close.transactionCostUsdt)
        assertEquals(BigDecimal("26.102086800000"), close.netProceedsUsdt)
        assertEquals(BigDecimal("5.652086800000"), close.realizedPnlUsdt)
        assertEquals(BigDecimal("-8.349651600000"), assertNotNull(scenario(view, DipSample.STOP_LOSS).close).realizedPnlUsdt)
        assertNull(scenario(view, DipSample.NO_EXIT_FILL).close)
        assertEquals(BigDecimal("-2.697564800000"), view.realizedPnlUsdt)
        assertEquals(31, view.observations.size)
        assertEquals(BigDecimal("0.00002000000000000000000000000000000000"), view.observations.first().priceUsdt)
        assertNull(scenario(view, DipSample.HORIZON).close)
    }

    @Test
    fun `a leg in another minute uses that minute's rate`() {
        val view = convert(RATES, moveObservation = TAKE_PROFIT_EXIT to SECOND_MINUTE)

        val close = assertNotNull(scenario(view, DipSample.TAKE_PROFIT).close)
        assertEquals(SECOND_MINUTE.withoutSeconds(), close.minute)
        assertEquals(BigDecimal("19.576565100000"), close.netProceedsUsdt)
        assertEquals(BigDecimal("-0.873434900000"), close.realizedPnlUsdt)
        assertEquals(BigDecimal("20.450000000000"), assertNotNull(scenario(view, DipSample.TAKE_PROFIT).fill).costBasisUsdt)
    }

    @Test
    fun `a missing close rate nulls only that position while the others convert and the total is partial`() {
        val view = convert(RATES, moveObservation = TAKE_PROFIT_EXIT to UNRATED)

        val takeProfit = scenario(view, DipSample.TAKE_PROFIT)
        assertEquals(BigDecimal("20.450000000000"), assertNotNull(takeProfit.fill).costBasisUsdt)
        assertNull(assertNotNull(takeProfit.close).realizedPnlUsdt)
        assertEquals("NO_MARKET_DATA", assertNotNull(takeProfit.close).unavailableReason)
        assertEquals(BigDecimal("-8.349651600000"), assertNotNull(scenario(view, DipSample.STOP_LOSS).close).realizedPnlUsdt)
        assertNull(view.realizedPnlUsdt)
        assertEquals("PARTIAL", view.realizedPnlUnavailableReason)
    }

    @Test
    fun `a missing fill rate nulls the position's P&L though its close converted`() {
        val view = convert(RATES, moveObservation = TAKE_PROFIT_FILL to UNRATED)

        val takeProfit = scenario(view, DipSample.TAKE_PROFIT)
        val close = assertNotNull(takeProfit.close)
        assertEquals(BigDecimal("26.102086800000"), close.netProceedsUsdt)
        assertNull(close.realizedPnlUsdt)
        assertEquals("NO_MARKET_DATA", close.unavailableReason)
        assertEquals("NO_MARKET_DATA", assertNotNull(takeProfit.fill).unavailableReason)
        assertEquals("PARTIAL", view.realizedPnlUnavailableReason)
    }

    @Test
    fun `an observation quoted in another mint is not converted and its minute is never looked up`() {
        val usdc = MintAddress.of("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
        val template = dataset.observations.first()
        extra = listOf(template.copy(id = "other-quote", pair = MarketPair.of(template.pair.base, usdc), observedTime = UNRATED))

        val view = convert(RATES)

        val other = view.observations.single { it.id == "other-quote" }
        assertNull(other.priceUsdt)
        assertEquals("NOT_SOL_QUOTED", other.unavailableReason)
        assertEquals(RATES.keys, requested)
        assertEquals(BigDecimal("-2.697564800000"), view.realizedPnlUsdt)
    }

    @Test
    fun `a missing rate leaves its values and the total unavailable instead of a partial sum`() {
        val view = convert(emptyMap())

        assertNull(view.unavailableReason)
        assertEquals(List(31) { "NO_MARKET_DATA" }, view.observations.map { it.unavailableReason })
        assertNull(view.observations.first().priceUsdt)
        assertEquals(List(2) { "NO_MARKET_DATA" }, view.scenarios.mapNotNull { it.close?.unavailableReason })
        assertNull(view.realizedPnlUsdt)
        assertEquals("PARTIAL", view.realizedPnlUnavailableReason)
    }

    @Test
    fun `an unconfigured rate source makes the whole view unavailable`() {
        val view = convert(emptyMap(), missing = RateUnavailableReason.NOT_CONFIGURED)
        assertEquals("NOT_CONFIGURED", view.unavailableReason)
        assertNull(view.realizedPnlUsdt)
    }

    @Test
    fun `a run not quoted in wrapped SOL is unavailable without any rate lookup`() {
        val view = convert(RATES, quote = MintAddress.of("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"))
        assertEquals("NOT_SOL_QUOTED", view.unavailableReason)
        assertEquals(0, lookups)
    }

    private fun scenario(view: RunUsdtView, cycle: Int): UsdtScenario =
        view.scenarios.single { it.intentId == report.scenarios.single { s -> s.intent.pair == DipSample.pair(cycle) }.intent.id }

    private fun convert(
        rates: Map<Instant, RateLookup>,
        quote: MintAddress = MintAddress.WRAPPED_SOL,
        moveObservation: Pair<String, Instant>? = null,
        missing: RateUnavailableReason = RateUnavailableReason.NO_MARKET_DATA,
    ) = UsdtConversion.convert(detail(quote, moveObservation), observations(moveObservation)) { minutes ->
        lookups++
        requested += minutes
        minutes.associateWith { rates[it] ?: RateLookup.Unavailable(missing) }
    }

    private fun observations(move: Pair<String, Instant>?): List<MarketObservation> =
        dataset.observations.map { if (it.id == move?.first) it.copy(observedTime = move.second) else it } + extra

    private fun detail(quote: MintAddress, move: Pair<String, Instant>?): RunDetail {
        val byId = observations(move).associateBy { it.id }
        val scenarios = report.scenarios.map { result ->
            val selected = (result.outcome as? FillOutcome.Filled)?.let { byId[it.fill.observationId] }
            RunScenario(result.intent, result.outcome, selected, result.position, result.exitUnavailable)
        }
        val run = RunSummary(report.runId, report.datasetChecksum, report.engineVersion, report.configurationHash, report.resultChecksum,
            quote, 10, 5, 5, 3, 2, BigDecimal(DipSample.TOTAL_PNL), Instant.parse("2026-10-08T00:00:00Z"))
        return RunDetail(run, SimulationConfig.mvp(), scenarios)
    }

    private fun Instant.withoutSeconds(): Instant = Instant.ofEpochSecond(epochSecond - epochSecond % 60)

    private companion object {
        val MINUTE: Instant = Instant.parse("2026-10-01T00:00:00Z")
        val SECOND_MINUTE: Instant = Instant.parse("2026-10-01T00:01:01Z")
        val UNRATED: Instant = Instant.parse("2026-10-01T00:05:00Z")
        val RATES: Map<Instant, RateLookup> = listOf(MINUTE to "200", Instant.parse("2026-10-01T00:01:00Z") to "150",
            Instant.parse("2026-10-01T00:02:00Z") to "151")
            .associate { (minute, rate) -> minute to RateLookup.Available(BigDecimal(rate), minute, "binance:SOLUSDT:1m:open") }
        const val TAKE_PROFIT_FILL = "fixture:TP:1003:0"
        const val TAKE_PROFIT_EXIT = "fixture:TP:1006:0"
    }
}
