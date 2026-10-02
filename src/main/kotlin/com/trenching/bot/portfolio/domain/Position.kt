package com.trenching.bot.portfolio.domain

import com.trenching.bot.common.*
import com.trenching.bot.papertrading.domain.PaperFill
import java.math.BigDecimal

data class Position(
    val id: String,
    val runId: String,
    val intentId: String,
    val pair: MarketPair,
    val entryOffset: Int,
    val fill: PaperFill,
    val state: PositionState,
    val close: PositionClose?,
)

enum class PositionState { OPEN, CLOSED }

data class PositionClose(
    val slot: Slot,
    val observationId: String,
    val executionPrice: BigDecimal,
    val grossProceeds: BigDecimal,
    val fee: BigDecimal,
    val netProceeds: BigDecimal,
    val realizedPnl: BigDecimal,
    val reason: CloseReason,
)

enum class CloseReason { MAX_HOLDING_SLOTS }
