package com.trenching.bot.papertrading.domain

import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.ObservationQuality
import java.math.BigDecimal

data class PaperFill(
    val orderId: String,
    val targetSlot: Slot,
    val actualSlot: Slot,
    val observationId: String,
    val executionPrice: BigDecimal,
    val quantity: BigDecimal,
    val fee: BigDecimal,
    val costBasis: BigDecimal,
    val feeBps: Int,
    val slippageBps: Int,
    val priceSource: String,
    val priceAssumption: ObservationQuality,
    val priorityFee: BigDecimal,
    val priceImpactBps: Int,
)
