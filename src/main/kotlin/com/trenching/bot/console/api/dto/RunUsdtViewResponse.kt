package com.trenching.bot.console.api.dto

import java.time.Instant

/**
 * A run's values in USDT for display; every amount is an exact decimal string. A null value carries its
 * `unavailableReason`; a non-null top-level [unavailableReason] means nothing could be converted.
 */
data class RunUsdtViewResponse(
    val runId: String,
    val rateSource: String?,
    val unavailableReason: String?,
    val observations: List<UsdtObservationResponse>,
    val scenarios: List<UsdtScenarioResponse>,
    val realizedPnlUsdt: String?,
    val realizedPnlUnavailableReason: String?,
)

data class UsdtObservationResponse(
    val id: String,
    val minute: Instant,
    val rate: String?,
    val priceUsdt: String?,
    val unavailableReason: String?,
)

data class UsdtScenarioResponse(val intentId: String, val fill: UsdtFillResponse?, val close: UsdtCloseResponse?)

data class UsdtFillResponse(
    val minute: Instant,
    val rate: String?,
    val executionPriceUsdt: String?,
    val costBasisUsdt: String?,
    val feeUsdt: String?,
    val priorityFeeUsdt: String?,
    val unavailableReason: String?,
)

data class UsdtCloseResponse(
    val minute: Instant,
    val rate: String?,
    val executionPriceUsdt: String?,
    val grossProceedsUsdt: String?,
    val feeUsdt: String?,
    val transactionCostUsdt: String?,
    val netProceedsUsdt: String?,
    val realizedPnlUsdt: String?,
    val unavailableReason: String?,
)

/** The latest SOL price in USDT, or the reason it is unavailable. */
data class SolUsdtPriceResponse(
    val rate: String?,
    val asOf: Instant?,
    val source: String?,
    val unavailableReason: String?,
)
