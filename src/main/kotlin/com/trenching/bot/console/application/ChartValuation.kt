package com.trenching.bot.console.application

import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.domain.ValuationBasisReason
import com.trenching.bot.console.domain.ValuationBasisView
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.ReferenceRates
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.TotalSupplyValuation
import com.trenching.bot.marketdata.domain.ValuationOutcome
import com.trenching.bot.marketdata.domain.ValuationUnavailableReason
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import java.math.BigDecimal

/** What the chart can say about one observation's mcap. */
sealed interface PointValuation {
    data class Valued(val usdt: BigDecimal) : PointValuation

    /** The dataset has rates, but none for this observation's minute. */
    data object MissingRate : PointValuation

    /** No rates at all: the run's dataset is gone from the catalog or recorded none. */
    data object RatesUnavailable : PointValuation

    data class Unsupported(val reason: ValuationUnavailableReason) : PointValuation

    val mcap: BigDecimal? get() = (this as? Valued)?.usdt
}

/** An observation with its valuation, converted before any aggregation. */
data class ChartPoint(val observation: MarketObservation, val valuation: PointValuation) {
    val mcap: BigDecimal? get() = valuation.mcap
}

/** Chart-side use of [TotalSupplyValuation], which alone owns the formula. */
object ChartValuation {
    const val LABEL = "total-supply valuation (FDV), USDT"
    const val SUPPLY_SOURCE = "PUMPFUN_FIXED_SUPPLY"
    const val QUOTE_CURRENCY = "USDT"
    const val RATE_GRANULARITY = "MINUTE"
    private const val RATE_SOURCE_SEPARATOR = ","
    private val SUPPLY: String = PumpFunLayout.TOKEN_SUPPLY.toString()

    /** [rates] is null when none are available; a minute without a rate is never replaced by a neighbour's. */
    fun of(observation: MarketObservation, rates: ReferenceRates?): PointValuation {
        val rate = rates?.rateAt(observation.observedTime)
        // The rate does not decide whether the observation is supported, so a missing one is classified with a placeholder.
        return when (val outcome = TotalSupplyValuation.of(observation, rate ?: BigDecimal.ONE)) {
            is ValuationOutcome.Unavailable -> PointValuation.Unsupported(outcome.reason)
            is ValuationOutcome.Valued -> when {
                rates == null -> PointValuation.RatesUnavailable
                rate == null -> PointValuation.MissingRate
                else -> PointValuation.Valued(outcome.usdt)
            }
        }
    }

    /** The recorded rate sources, sorted and comma separated, or null for none. */
    fun rateSource(rates: List<ReferenceRate>): String? =
        rates.map { it.source }.distinct().sorted().joinToString(RATE_SOURCE_SEPARATOR).ifEmpty { null }

    fun basis(quote: MintAddress, rateSource: String?, ratesAvailable: Boolean, valuations: List<PointValuation>): ValuationBasisView {
        val reason = unavailableReason(quote, ratesAvailable, valuations)
        return ValuationBasisView(LABEL, SUPPLY, SUPPLY_SOURCE, QUOTE_CURRENCY, rateSource, RATE_GRANULARITY, reason == null, reason)
    }

    private fun unavailableReason(quote: MintAddress, ratesAvailable: Boolean, valuations: List<PointValuation>): ValuationBasisReason? = when {
        quote != MintAddress.WRAPPED_SOL -> ValuationBasisReason.NOT_SOL_QUOTED
        !ratesAvailable -> ValuationBasisReason.RATES_UNAVAILABLE
        valuations.isNotEmpty() && valuations.all { it is PointValuation.Unsupported } -> ValuationBasisReason.UNSUPPORTED_TOKEN
        else -> null
    }
}
