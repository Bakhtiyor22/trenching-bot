package com.trenching.bot.console.api.dto

/** The timed clocks are whole milliseconds, null when the run is untimed. */
data class ConfigResponse(
    val strategyName: String,
    val strategyVersion: String,
    val notional: String,
    val maxAgeSlots: Long,
    val dipBps: Int,
    val dipPercent: String,
    val takeProfitBps: Int,
    val takeProfitPercent: String,
    val stopLossBps: Int,
    val stopLossPercent: String,
    val minMarketCapUsd: String,
    val horizonSlots: Long,
    val latencySlots: Long,
    val transactionCostSol: String,
    val quoteMint: String,
    val dipWindowMs: Long?,
    val maxHoldMs: Long?,
    val hardExitStartMs: Long?,
)

data class ConfigDefaultsResponse(
    val defaults: ConfigResponse,
    val limits: ConfigLimitsResponse,
)

/**
 * Inclusive ranges; a null `max` is unbounded above. Each level's percent range is its basis point range as exact percent
 * strings. A notional has at most [notionalScale] decimals (whole lamports)
 * and [notionalPrecision] digits; a transaction cost is rounded to [transactionCostScale] decimals. The timed clocks' ranges
 * are milliseconds and bound a value only when it is set.
 */
data class ConfigLimitsResponse(
    val notionalScale: Int,
    val notionalPrecision: Int,
    val maxAgeSlots: RangeResponse,
    val dipBps: RangeResponse,
    val dipPercent: DecimalRangeResponse,
    val takeProfitBps: RangeResponse,
    val takeProfitPercent: DecimalRangeResponse,
    val stopLossBps: RangeResponse,
    val stopLossPercent: DecimalRangeResponse,
    val minMarketCapUsd: RangeResponse,
    val horizonSlots: RangeResponse,
    val latencySlots: RangeResponse,
    val transactionCostScale: Int,
    val transactionCostSol: DecimalRangeResponse,
    val dipWindowMs: RangeResponse,
    val maxHoldMs: RangeResponse,
    val hardExitStartMs: RangeResponse,
)

data class RangeResponse(
    val min: Long,
    val max: Long?,
)

/** Inclusive decimal bounds as exact decimal strings. */
data class DecimalRangeResponse(
    val min: String,
    val max: String,
)
