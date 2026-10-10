package com.trenching.bot.console.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.domain.RunDetail
import com.trenching.bot.console.domain.RunUsdtView
import com.trenching.bot.console.domain.UsdtClose
import com.trenching.bot.console.domain.UsdtFill
import com.trenching.bot.console.domain.UsdtObservation
import com.trenching.bot.console.domain.UsdtScenario
import com.trenching.bot.marketdata.RateLookup
import com.trenching.bot.marketdata.RateUnavailableReason
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.papertrading.domain.FillOutcome
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Converts a stored SOL-quoted run to USDT for display. Prices are multiplied by the rate of their block time's minute at
 * the price scale; amounts at the amount scale; both HALF_EVEN. Nothing is interpolated: a value without a rate is null
 * with the rate's reason, and the run total is null (`PARTIAL`) unless every closed position converted.
 */
object UsdtConversion {
    const val NOT_SOL_QUOTED = "NOT_SOL_QUOTED"
    const val PARTIAL = "PARTIAL"

    /** [lookup] is asked once, for the minutes of every observation, and only for a wrapped-SOL run. */
    fun convert(detail: RunDetail, observations: List<MarketObservation>, lookup: (Set<Instant>) -> Map<Instant, RateLookup>): RunUsdtView {
        val runId = detail.run.id
        if (detail.run.quoteMint != MintAddress.WRAPPED_SOL) return unavailable(runId, NOT_SOL_QUOTED)
        // A dataset may hold pairs in other quotes; only SOL-quoted prices convert at a SOL/USDT rate.
        val rates = lookup(observations.filter(::solQuoted).mapTo(HashSet()) { minute(it.observedTime) })
        val lookups = rates.values
        if (lookups.isNotEmpty() && lookups.all { it == RateLookup.Unavailable(RateUnavailableReason.NOT_CONFIGURED) }) {
            return unavailable(runId, RateUnavailableReason.NOT_CONFIGURED.name)
        }
        val byId = observations.associateBy { it.id }
        fun rateAt(time: Instant): RateLookup = rates[minute(time)] ?: error("Rate source omitted a requested minute runId=$runId minute=${minute(time)}")
        val scenarios = detail.scenarios.map { scenario ->
            val fill = (scenario.outcome as? FillOutcome.Filled)?.fill
            val usdtFill = if (fill != null) {
                val fillTime = checkNotNull(byId[fill.observationId]) { "Missing fill observation runId=$runId id=${fill.observationId}" }.observedTime
                val rate = rateAt(fillTime)
                UsdtFill(minute(fillTime), rateOf(rate), price(fill.executionPrice, rate), amount(fill.costBasis, rate), amount(fill.fee, rate),
                    amount(fill.priorityFee, rate), reasonOf(rate))
            } else null
            val close = scenario.position?.close
            val usdtClose = if (close != null && usdtFill != null) {
                val closeTime = checkNotNull(byId[close.observationId]) { "Missing close observation runId=$runId id=${close.observationId}" }.observedTime
                val rate = rateAt(closeTime)
                val net = amount(close.netProceeds, rate)
                val cost = usdtFill.costBasisUsdt
                UsdtClose(minute(closeTime), rateOf(rate), price(close.executionPrice, rate), amount(close.grossProceeds, rate),
                    amount(close.fee, rate), amount(close.transactionCost, rate), net, if (net != null && cost != null) net.subtract(cost) else null,
                    reasonOf(rate) ?: usdtFill.unavailableReason)
            } else null
            UsdtScenario(scenario.intent.id, usdtFill, usdtClose)
        }
        val closes = scenarios.mapNotNull { it.close }
        val total = if (closes.all { it.realizedPnlUsdt != null }) closes.fold(DecimalPolicy.scale(BigDecimal.ZERO)) { sum, close ->
            sum.add(close.realizedPnlUsdt)
        } else null
        return RunUsdtView(
            runId = runId,
            rateSource = lookups.filterIsInstance<RateLookup.Available>().map { it.source }.distinct().singleOrNull(),
            unavailableReason = null,
            observations = observations.map { observation ->
                val minute = minute(observation.observedTime)
                if (!solQuoted(observation)) UsdtObservation(observation.id, minute, null, null, NOT_SOL_QUOTED)
                else rateAt(observation.observedTime).let { rate ->
                    UsdtObservation(observation.id, minute, rateOf(rate), price(observation.price, rate), reasonOf(rate))
                }
            },
            scenarios = scenarios,
            realizedPnlUsdt = total,
            realizedPnlUnavailableReason = if (total == null) PARTIAL else null,
        )
    }

    fun unavailable(runId: String, reason: String) = RunUsdtView(runId, null, reason, emptyList(), emptyList(), null, reason)

    private fun minute(time: Instant): Instant = time.truncatedTo(ChronoUnit.MINUTES)

    private fun solQuoted(observation: MarketObservation): Boolean = observation.pair.quote == MintAddress.WRAPPED_SOL

    private fun rateOf(lookup: RateLookup): BigDecimal? = (lookup as? RateLookup.Available)?.rate

    private fun reasonOf(lookup: RateLookup): String? = (lookup as? RateLookup.Unavailable)?.reason?.name

    private fun price(value: BigDecimal, lookup: RateLookup): BigDecimal? = rateOf(lookup)?.let { DecimalPolicy.price(value.multiply(it)) }

    private fun amount(value: BigDecimal, lookup: RateLookup): BigDecimal? = rateOf(lookup)?.let { DecimalPolicy.scale(value.multiply(it)) }
}
