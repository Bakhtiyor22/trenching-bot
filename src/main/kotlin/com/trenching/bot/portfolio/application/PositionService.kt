package com.trenching.bot.portfolio.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.marketdata.MarketDataSource
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationEligibility
import com.trenching.bot.papertrading.CurveExecution
import com.trenching.bot.papertrading.domain.*
import com.trenching.bot.portfolio.domain.*
import com.trenching.bot.protocol.pumpfun.BondingCurve
import com.trenching.bot.strategy.domain.*
import java.math.BigDecimal

/**
 * IN_POSITION of the dip-entry strategy: the first eligible trade after the fill at or above take profit, or at or below
 * stop loss, triggers the exit. Per the bonding-curve-execution ADR the exit fills at the first eligible trade at least
 * `latencySlots` after the trigger whose post-trade curve can pay the sale, selling the whole position into that curve.
 * Nothing after the bonding curve completes, or after the horizon, is used.
 */
class PositionService {
    fun open(intent: TradeIntent, fill: PaperFill, config: SimulationConfig): Position {
        require(intent.id == fill.orderId) { "Fill does not belong to intent" }
        require(fill.actualSlot >= intent.targetSlot && fill.quantity.signum() > 0 && fill.costBasis.signum() > 0) {
            "Invalid entry fill"
        }
        val takeProfit = DecimalPolicy.price(fill.executionPrice.multiply(BigDecimal.ONE.add(DecimalPolicy.fraction(config.takeProfitBps))))
        val stopLoss = DecimalPolicy.price(fill.executionPrice.multiply(BigDecimal.ONE.subtract(DecimalPolicy.fraction(config.stopLossBps))))
        return Position(intent.id, intent.runId, intent.id, intent.pair, intent.entryOffset, fill, PositionState.OPEN, null,
            takeProfit, stopLoss, null)
    }

    fun close(position: Position, intent: TradeIntent, source: MarketDataSource, config: SimulationConfig): CloseOutcome {
        require((position.state == PositionState.CLOSED) == (position.close != null)) { "Inconsistent position state" }
        if (position.state == PositionState.CLOSED) return CloseOutcome.AlreadyClosed(position)
        val rawTokens = CurveExecution.rawTokens(position.fill.quantity)
        var trigger: ExitTrigger? = null
        for (observation in source.observations(position.pair, position.fill.actualSlot.plus(1), intent.horizonEndSlot)) {
            if (observation.curveComplete) return CloseOutcome.Unavailable(position.copy(exitTrigger = trigger), RejectionReason.MIGRATED)
            if (ObservationEligibility.issue(observation, config.maxAgeSlots) != null) continue
            val crossed = trigger
            if (crossed == null) {
                trigger = crossing(position, observation)
            } else if (observation.slot >= crossed.slot.plus(config.latencySlots)) {
                val sold = BondingCurve.sell(observation.curve.reserves, rawTokens) ?: continue
                return exit(position.copy(exitTrigger = crossed), observation, CurveExecution.trade(observation.curve, sold.lamportsOut, rawTokens),
                    crossed.reason, config)
            }
        }
        val reason = if (trigger == null) RejectionReason.HORIZON_REACHED else RejectionReason.NO_EXIT_FILL
        return CloseOutcome.Unavailable(position.copy(exitTrigger = trigger), reason)
    }

    private fun crossing(position: Position, observation: MarketObservation): ExitTrigger? = when {
        observation.price >= position.takeProfitPrice -> ExitTrigger(observation.id, observation.slot, CloseReason.TAKE_PROFIT)
        observation.price <= position.stopLossPrice -> ExitTrigger(observation.id, observation.slot, CloseReason.STOP_LOSS)
        else -> null
    }

    /** A net at or below zero still closes: the loss is real and is never dropped. */
    private fun exit(position: Position, observation: MarketObservation, sale: CurveTrade, reason: CloseReason, config: SimulationConfig): CloseOutcome {
        val gross = CurveExecution.sol(sale.lamports)
        val fee = CurveExecution.sol(sale.feeLamports)
        val net = DecimalPolicy.scale(gross.subtract(fee).subtract(config.transactionCostSol))
        val close = PositionClose(observation.slot, observation.id, sale.executionPrice, gross, fee, sale.feeBps, config.transactionCostSol,
            sale.priceImpactBps, net, DecimalPolicy.scale(net.subtract(position.fill.costBasis)), reason)
        return CloseOutcome.Closed(position.copy(state = PositionState.CLOSED, close = close))
    }
}
