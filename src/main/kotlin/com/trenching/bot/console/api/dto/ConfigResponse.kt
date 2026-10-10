package com.trenching.bot.console.api.dto

data class ConfigResponse(
    val strategyName: String,
    val strategyVersion: String,
    val notional: String,
    val maxAgeSlots: Long,
    val dipBps: Int,
    val takeProfitBps: Int,
    val stopLossBps: Int,
    val minMarketCapUsd: String,
    val horizonSlots: Long,
    val latencySlots: Long,
    val transactionCostSol: String,
    val quoteMint: String,
)

data class ConfigDefaultsResponse(
    val defaults: ConfigResponse,
    val limits: ConfigLimitsResponse,
)

/**
 * Inclusive ranges; a null `max` is unbounded above. A notional has at most [notionalScale] decimals (whole lamports)
 * and [notionalPrecision] digits; a transaction cost is rounded to [transactionCostScale] decimals.
 */
data class ConfigLimitsResponse(
    val notionalScale: Int,
    val notionalPrecision: Int,
    val maxAgeSlots: RangeResponse,
    val dipBps: RangeResponse,
    val takeProfitBps: RangeResponse,
    val stopLossBps: RangeResponse,
    val minMarketCapUsd: RangeResponse,
    val horizonSlots: RangeResponse,
    val latencySlots: RangeResponse,
    val transactionCostScale: Int,
    val transactionCostSol: DecimalRangeResponse,
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
