package com.trenching.bot.console.api.dto

data class ConfigResponse(
    val strategyName: String,
    val strategyVersion: String,
    val notional: String,
    val feeBps: Int,
    val slippageBps: Int,
    val maxWaitSlots: Long,
    val maxAgeSlots: Long,
    val holdingSlots: Long,
    val quoteMint: String,
)

data class ConfigDefaultsResponse(
    val defaults: ConfigResponse,
    val limits: ConfigLimitsResponse,
)

/** Inclusive ranges; a null `max` is unbounded above. */
data class ConfigLimitsResponse(
    val notionalScale: Int,
    val notionalPrecision: Int,
    val feeBps: RangeResponse,
    val slippageBps: RangeResponse,
    val maxWaitSlots: RangeResponse,
    val maxAgeSlots: RangeResponse,
    val holdingSlots: RangeResponse,
)

data class RangeResponse(
    val min: Long,
    val max: Long?,
)
