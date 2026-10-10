package com.trenching.bot.portfolio.domain

import com.trenching.bot.common.*
import com.trenching.bot.papertrading.domain.PaperFill
import java.math.BigDecimal

/**
 * A dip-entry position. [takeProfitPrice] and [stopLossPrice] are measured from the entry's execution price;
 * [exitTrigger] is the trade that crossed one of them, set even when no later trade filled the exit.
 */
data class Position(
    val id: String,
    val runId: String,
    val intentId: String,
    val pair: MarketPair,
    val entryOffset: Int,
    val fill: PaperFill,
    val state: PositionState,
    val close: PositionClose?,
    val takeProfitPrice: BigDecimal,
    val stopLossPrice: BigDecimal,
    val exitTrigger: ExitTrigger?,
)

enum class PositionState { OPEN, CLOSED }

data class ExitTrigger(val observationId: String, val slot: Slot, val reason: CloseReason)

/**
 * A sale against the exit trade's post-trade curve: [grossProceeds] before the curve's [fee] (charged at [feeBps]) and the
 * configured [transactionCost]; [priceImpactBps] compares [executionPrice] with that curve's spot price.
 */
data class PositionClose(
    val slot: Slot,
    val observationId: String,
    val executionPrice: BigDecimal,
    val grossProceeds: BigDecimal,
    val fee: BigDecimal,
    val feeBps: Int,
    val transactionCost: BigDecimal,
    val priceImpactBps: Int,
    val netProceeds: BigDecimal,
    val realizedPnl: BigDecimal,
    val reason: CloseReason,
)

enum class CloseReason { TAKE_PROFIT, STOP_LOSS }
