package com.trenching.bot.console.api.dto

/** Entry economics; every amount is an exact decimal string in quote units, [quantity] in base units. */
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
    val slippageBps: Int,
    val priceSource: String,
    val priceAssumption: String,
    val priorityFee: String,
    val priceImpactBps: Int,
)

/** Exit economics of a closed position, exact decimal strings in quote units. */
data class CloseResponse(
    val slot: Long,
    val observationId: String,
    val executionPrice: String,
    val grossProceeds: String,
    val fee: String,
    val netProceeds: String,
    val realizedPnl: String,
    val reason: String,
)
