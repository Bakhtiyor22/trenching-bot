package com.trenching.bot.console.api.dto

/**
 * Entry economics; every amount is an exact decimal string in quote units, [quantity] in base units. [priorityFee] is
 * the configured transaction cost and [priceImpactBps] the curve's move against the buy; [slippageBps] is always 0. Each
 * `…Percent` is its basis point twin as an exact signed percent string.
 */
data class FillResponse(
    val orderId: String,
    val targetSlot: Long,
    val actualSlot: Long,
    val observationId: String,
    val executionPrice: String,
    val quantity: String,
    val fee: String,
    val costBasis: String,
    val feeBps: Int,
    val feePercent: String,
    val slippageBps: Int,
    val priceSource: String,
    val priceAssumption: String,
    val priorityFee: String,
    val priceImpactBps: Int,
    val priceImpactPercent: String,
)

/**
 * Exit economics of a closed position, exact decimal strings in quote units: [netProceeds] is [grossProceeds] less
 * [fee] (charged at [feeBps]) and [transactionCost]; [priceImpactBps] is negative for the sale. Each `…Percent` is its
 * basis point twin as an exact signed percent string.
 */
data class CloseResponse(
    val slot: Long,
    val observationId: String,
    val executionPrice: String,
    val grossProceeds: String,
    val fee: String,
    val feeBps: Int,
    val feePercent: String,
    val transactionCost: String,
    val priceImpactBps: Int,
    val priceImpactPercent: String,
    val netProceeds: String,
    val realizedPnl: String,
    val reason: String,
)
