package com.trenching.bot.portfolio.domain

import com.trenching.bot.common.*
import com.trenching.bot.papertrading.domain.PaperFill
import java.math.BigDecimal
import java.time.Instant

/**
 * A dip-entry position. [takeProfitPrice] and [stopLossPrice] are measured from the entry's execution price; [exitStart] is
 * when a timed configuration's time exit starts (null without one, or without the buy's block time to count from);
 * [exitTrigger] is what started the exit, set even when no sale could be made.
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
    val exitStart: Instant?,
)

enum class PositionState { OPEN, CLOSED }

/**
 * A take profit or stop loss trigger is the trade that crossed its level, at that trade's [slot] and block [time]. A time exit
 * starts at [time] = the position's exit start; its [observationId] is the trade whose post-trade curve stood then, and its
 * [slot] the first slot the recording proves at or after that moment: the first trade at or after it, or else the coverage end.
 * [time] is null only on a trigger stored before engine dip-paper-v4.
 */
data class ExitTrigger(val observationId: String, val slot: Slot, val time: Instant?, val reason: CloseReason)

/**
 * A sale of the whole position against one post-trade curve, the one of [observationId], at [slot]: [grossProceeds] before
 * the curve's [fee] (charged at [feeBps]) and the configured [transactionCost]; [priceImpactBps] compares [executionPrice]
 * with that curve's spot price. [priceAssumption] says whether the curve is the sale trade's own or the one standing when it
 * was made.
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
    val priceAssumption: ExitPriceAssumption,
)

enum class CloseReason { TAKE_PROFIT, STOP_LOSS, TIME_EXIT }

/**
 * [NEXT_TRADE]: sold into the first eligible trade at least `latencySlots` after the trigger and within its [ExitSaleWindow],
 * against its post-trade curve. [STANDING_CURVE]: no such trade was recorded, so sold against the curve standing at the sale's
 * moment, the post-trade curve of the last eligible trade before it: `trigger.slot + latencySlots` for take profit and stop
 * loss, the exit start for a time exit.
 */
enum class ExitPriceAssumption { NEXT_TRADE, STANDING_CURVE }
