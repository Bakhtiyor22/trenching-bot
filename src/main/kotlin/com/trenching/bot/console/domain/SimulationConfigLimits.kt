package com.trenching.bot.console.domain

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.strategy.domain.SimulationConfigBounds
import java.math.BigDecimal

/** Inclusive bounds; a null [max] means unbounded above. */
data class NumberRange(val min: Long, val max: Long?) {
    operator fun contains(value: Long): Boolean = value >= min && (max == null || value <= max)
}

/** Inclusive decimal bounds. */
data class DecimalRange(val min: BigDecimal, val max: BigDecimal) {
    operator fun contains(value: BigDecimal): Boolean = value >= min && value <= max
}

/**
 * Editable ranges sourced from strategy so each field can be reported separately. A notional has at most
 * [notionalScale] decimals (whole lamports) and [notionalPrecision] digits at that scale; a transaction cost is
 * rounded to [transactionCostScale] decimals.
 */
data class SimulationConfigLimits(
    val notionalScale: Int,
    val notionalPrecision: Int,
    val maxAgeSlots: NumberRange,
    val dipBps: NumberRange,
    val takeProfitBps: NumberRange,
    val stopLossBps: NumberRange,
    val minMarketCapUsd: NumberRange,
    val horizonSlots: NumberRange,
    val latencySlots: NumberRange,
    val transactionCostScale: Int,
    val transactionCostSol: DecimalRange,
) {
    companion object {
        private val LEVELS = range(SimulationConfigBounds.LEVEL_BASIS_POINTS)

        val CURRENT = SimulationConfigLimits(
            notionalScale = SimulationConfigBounds.NOTIONAL_DECIMALS,
            notionalPrecision = DecimalPolicy.STORAGE_PRECISION - DecimalPolicy.SCALE + SimulationConfigBounds.NOTIONAL_DECIMALS,
            maxAgeSlots = range(SimulationConfigBounds.MAX_AGE_SLOTS),
            dipBps = LEVELS,
            takeProfitBps = LEVELS,
            stopLossBps = LEVELS,
            minMarketCapUsd = NumberRange(0, SimulationConfigBounds.MAX_MIN_MARKET_CAP_USD.longValueExact()),
            horizonSlots = range(SimulationConfigBounds.HORIZON_SLOTS),
            latencySlots = range(SimulationConfigBounds.LATENCY_SLOTS),
            transactionCostScale = DecimalPolicy.SCALE,
            transactionCostSol = DecimalRange(BigDecimal.ZERO, SimulationConfigBounds.MAX_TRANSACTION_COST_SOL),
        )

        private fun range(values: IntRange) = NumberRange(values.first.toLong(), values.last.toLong())

        private fun range(values: LongRange) = NumberRange(values.first, values.last)
    }
}
