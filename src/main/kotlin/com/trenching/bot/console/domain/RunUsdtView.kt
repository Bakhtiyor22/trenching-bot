package com.trenching.bot.console.domain

import java.math.BigDecimal
import java.time.Instant

/**
 * A SOL-quoted run's values in USDT, display only. Each value is converted at the minute of its own block time; a value
 * without a rate is null with its reason. [unavailableReason] is set when the whole view cannot be converted.
 */
data class RunUsdtView(
    val runId: String,
    val rateSource: String?,
    val unavailableReason: String?,
    val observations: List<UsdtObservation>,
    val scenarios: List<UsdtScenario>,
    val realizedPnlUsdt: BigDecimal?,
    val realizedPnlUnavailableReason: String?,
)

data class UsdtObservation(
    val id: String,
    val minute: Instant,
    val rate: BigDecimal?,
    val priceUsdt: BigDecimal?,
    val unavailableReason: String?,
)

data class UsdtScenario(val intentId: String, val fill: UsdtFill?, val close: UsdtClose?)

data class UsdtFill(
    val minute: Instant,
    val rate: BigDecimal?,
    val executionPriceUsdt: BigDecimal?,
    val costBasisUsdt: BigDecimal?,
    val feeUsdt: BigDecimal?,
    val priorityFeeUsdt: BigDecimal?,
    val unavailableReason: String?,
)

/** [realizedPnlUsdt] = net proceeds at the close minute's rate − cost basis at the fill minute's rate. */
data class UsdtClose(
    val minute: Instant,
    val rate: BigDecimal?,
    val executionPriceUsdt: BigDecimal?,
    val grossProceedsUsdt: BigDecimal?,
    val feeUsdt: BigDecimal?,
    val transactionCostUsdt: BigDecimal?,
    val netProceedsUsdt: BigDecimal?,
    val realizedPnlUsdt: BigDecimal?,
    val unavailableReason: String?,
)
