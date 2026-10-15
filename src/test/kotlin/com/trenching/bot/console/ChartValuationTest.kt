package com.trenching.bot.console

import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.ChartFixtures.OTHER_QUOTE_PAIR
import com.trenching.bot.console.ChartFixtures.at
import com.trenching.bot.console.ChartFixtures.observation
import com.trenching.bot.console.application.ChartPoint
import com.trenching.bot.console.application.ChartValuation
import com.trenching.bot.console.application.PointValuation
import com.trenching.bot.console.domain.ValuationBasisReason
import com.trenching.bot.marketdata.domain.OrderedReferenceRates
import com.trenching.bot.marketdata.domain.ValuationUnavailableReason
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ChartValuationTest {
    private val firstMinute = at("2026-10-01T00:00:00Z")
    private val rates = OrderedReferenceRates(listOf(
        ReferenceRate(firstMinute, BigDecimal("150.5"), SOURCE),
        ReferenceRate(firstMinute.plusSeconds(60), BigDecimal("300"), SOURCE),
    ))

    @Test
    fun `a known point is price times the minute's rate times the supply at the policy scale`() {
        val point = observation("p", at("2026-10-01T00:00:30Z"), "0.00000002")

        assertEquals(PointValuation.Valued(BigDecimal("3010.000000000000")), ChartValuation.of(point, rates))
    }

    @Test
    fun `each observation is converted at the rate of its own minute`() {
        val later = observation("q", at("2026-10-01T00:01:59Z"), "0.00000002")

        assertEquals(PointValuation.Valued(BigDecimal("6000.000000000000")), ChartValuation.of(later, rates))
    }

    @Test
    fun `it equals the market cap the dip strategy judged for the sample's trigger`() {
        val dataset = DipSample.load()
        val sampleRates = OrderedReferenceRates(dataset.referenceRates)
        val triggers = ReplayRunner().run(dataset, SimulationConfig.mvp()).scenarios.mapNotNull { it.intent.trigger }
        assertTrue(triggers.isNotEmpty())

        triggers.forEach { trigger ->
            val observation = dataset.observations.single { it.id == trigger.observationId }
            assertEquals(PointValuation.Valued(trigger.marketCapUsd), ChartValuation.of(observation, sampleRates), trigger.observationId)
        }
    }

    @Test
    fun `a minute without a recorded rate is a missing rate and never a neighbour's`() {
        val unrated = observation("p", at("2026-10-01T00:05:00Z"), "0.00000002")

        assertEquals(PointValuation.MissingRate, ChartValuation.of(unrated, rates))
    }

    @Test
    fun `without rates every SOL-quoted point has none available`() {
        assertEquals(PointValuation.RatesUnavailable, ChartValuation.of(observation("p", firstMinute, "0.00000002"), null))
    }

    @Test
    fun `a structural problem wins over a missing rate`() {
        val unrated = at("2026-10-01T00:05:00Z")

        assertEquals(PointValuation.Unsupported(ValuationUnavailableReason.NOT_SOL_QUOTED),
            ChartValuation.of(observation("p", unrated, "1", pair = OTHER_QUOTE_PAIR), rates))
        assertEquals(PointValuation.Unsupported(ValuationUnavailableReason.UNSUPPORTED_TOKEN),
            ChartValuation.of(observation("p", unrated, "1", source = "pumpswap-trade/v1"), null))
    }

    @Test
    fun `the basis states the formula, supply, source and granularity of a valued chart`() {
        val basis = ChartValuation.basis(MintAddress.WRAPPED_SOL, SOURCE, true, listOf(PointValuation.Valued(BigDecimal.ONE)))

        assertTrue(basis.available)
        assertNull(basis.unavailableReason)
        assertEquals("total-supply valuation (FDV), USDT", basis.label)
        assertEquals("1000000000", basis.supply)
        assertEquals("PUMPFUN_FIXED_SUPPLY", basis.supplySource)
        assertEquals("USDT", basis.quoteCurrency)
        assertEquals(SOURCE, basis.rateSource)
        assertEquals("MINUTE", basis.rateGranularity)
    }

    @Test
    fun `a chart whose rates are gone is unavailable as rates unavailable`() {
        val basis = ChartValuation.basis(MintAddress.WRAPPED_SOL, null, false, listOf(PointValuation.RatesUnavailable))

        assertFalse(basis.available)
        assertEquals(ValuationBasisReason.RATES_UNAVAILABLE, basis.unavailableReason)
        assertNull(basis.rateSource)
    }

    @Test
    fun `a pair not quoted in SOL is unavailable even without points`() {
        assertEquals(ValuationBasisReason.NOT_SOL_QUOTED, ChartValuation.basis(MintAddress.of(ChartFixtures.OTHER_MINT), SOURCE, true, emptyList()).unavailableReason)
    }

    @Test
    fun `a chart where every point is unsupported is an unsupported token and a mismatch counts as unsupported`() {
        val unsupported = listOf(PointValuation.Unsupported(ValuationUnavailableReason.DECIMALS_MISMATCH),
            PointValuation.Unsupported(ValuationUnavailableReason.UNSUPPORTED_TOKEN))

        assertEquals(ValuationBasisReason.UNSUPPORTED_TOKEN, ChartValuation.basis(MintAddress.WRAPPED_SOL, SOURCE, true, unsupported).unavailableReason)
    }

    @Test
    fun `one valued point keeps the basis available and missing rates do not make it unavailable`() {
        val mixed = listOf(PointValuation.MissingRate, PointValuation.Unsupported(ValuationUnavailableReason.DECIMALS_MISMATCH))

        assertTrue(ChartValuation.basis(MintAddress.WRAPPED_SOL, SOURCE, true, mixed).available)
    }

    @Test
    fun `a chart point exposes its mcap only when valued`() {
        val observation = observation("p", firstMinute, "1", trade = null)

        assertEquals(BigDecimal.TEN, ChartPoint(observation, PointValuation.Valued(BigDecimal.TEN)).mcap)
        assertNull(ChartPoint(observation, PointValuation.MissingRate).mcap)
    }

    private companion object {
        const val SOURCE = "test-fixture:SOLUSDT:1m:open"
    }
}
