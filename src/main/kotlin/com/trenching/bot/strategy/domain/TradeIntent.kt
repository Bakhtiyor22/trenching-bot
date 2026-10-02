package com.trenching.bot.strategy.domain

import com.trenching.bot.common.*
import java.math.BigDecimal

data class TradeIntent(
    val id: String,
    val runId: String,
    val sourceEventId: String,
    val pair: MarketPair,
    val strategyName: String,
    val strategyVersion: String,
    val configurationHash: String,
    val entryOffset: Int,
    val creationSlot: Slot,
    val targetSlot: Slot,
    val notional: BigDecimal,
)
