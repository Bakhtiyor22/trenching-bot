package com.trenching.bot.portfolio.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.MarketDataSource
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationEligibility
import com.trenching.bot.papertrading.CurveExecution
import com.trenching.bot.papertrading.domain.*
import com.trenching.bot.portfolio.domain.*
import com.trenching.bot.protocol.pumpfun.BondingCurve
import com.trenching.bot.strategy.domain.*
import java.math.BigDecimal
import java.time.Instant

/**
 * IN_POSITION of the dip-entry strategy. Take profit and stop loss trigger on the first eligible trade after the fill that
 * crosses their level before the exit start. Per the bonding-curve-execution ADR such an exit sells the whole position into
 * the first eligible trade at least `latencySlots` after the trigger whose post-trade curve can pay it, but only one landing
 * within spec Section 8's 60 s of the trigger ([ExitSaleWindow]); without one it sells against the curve standing at its sale
 * slot. A timed configuration (spec P11) also triggers at the exit start when neither came first, selling against the curve
 * standing then.
 *
 * A sale against the standing curve is made only when the recording proves it covers the sale's moment, since no recorded
 * trade then shows nothing otherwise. Nothing after the bonding curve completes, or after the horizon, is used.
 */
class PositionService {
    fun open(intent: TradeIntent, fill: PaperFill, source: MarketDataSource, config: SimulationConfig): Position {
        require(intent.id == fill.orderId) { "Fill does not belong to intent" }
        require(fill.actualSlot >= intent.targetSlot && fill.quantity.signum() > 0 && fill.costBasis.signum() > 0) {
            "Invalid entry fill"
        }
        val takeProfit = DecimalPolicy.price(fill.executionPrice.multiply(BigDecimal.ONE.add(DecimalPolicy.fraction(config.takeProfitBps))))
        val stopLoss = DecimalPolicy.price(fill.executionPrice.multiply(BigDecimal.ONE.subtract(DecimalPolicy.fraction(config.stopLossBps))))
        return Position(intent.id, intent.runId, intent.id, intent.pair, intent.entryOffset, fill, PositionState.OPEN, null,
            takeProfit, stopLoss, null, exitStart(intent, fill, source, config))
    }

    /** [recorded] is how far the recording of the position's pair is proven complete, for the sales no trade can show. */
    fun close(position: Position, intent: TradeIntent, source: MarketDataSource, config: SimulationConfig, recorded: RecordedThrough): CloseOutcome {
        require((position.state == PositionState.CLOSED) == (position.close != null)) { "Inconsistent position state" }
        if (position.state == PositionState.CLOSED) return CloseOutcome.AlreadyClosed(position)
        var standing = standingAfterFillSlot(position, source, config)
            ?: return CloseOutcome.Unavailable(position, RejectionReason.MIGRATED)
        val exitStart = position.exitStart
        if (exitStart != null && !fillTrade(position.fill, position.pair, source).observedTime.isBefore(exitStart)) {
            return timeExit(position, standing, position.fill.actualSlot, exitStart, config)
        }
        val held = source.observations(position.pair, position.fill.actualSlot.plus(1), intent.horizonEndSlot)
        for ((index, observation) in held.withIndex()) {
            if (exitStart != null && !observation.observedTime.isBefore(exitStart)) return timeExit(position, standing, observation.slot, exitStart, config)
            if (observation.curveComplete) return CloseOutcome.Unavailable(position, RejectionReason.MIGRATED)
            if (!eligible(observation, config)) continue
            standing = observation
            val trigger = crossing(position, observation) ?: continue
            return sell(position.copy(exitTrigger = trigger), observation, held.subList(index + 1, held.size), recorded, config)
        }
        val reached = recorded.time
        return if (exitStart != null && reached != null && !reached.isBefore(exitStart)) {
            timeExit(position, standing, recorded.slot, exitStart, config)
        } else CloseOutcome.Unavailable(position, RejectionReason.HORIZON_REACHED)
    }

    /**
     * Sells [triggered] after its take profit or stop loss at [crossing]: into the first eligible trade of [later] within its
     * [ExitSaleWindow] whose curve can pay, or else, once the recording proves no trade can land within it, against the curve
     * standing at the sale slot.
     */
    private fun sell(
        triggered: Position,
        crossing: MarketObservation,
        later: List<MarketObservation>,
        recorded: RecordedThrough,
        config: SimulationConfig,
    ): CloseOutcome {
        val window = ExitSaleWindow.of(checkNotNull(triggered.exitTrigger) { "Sale without a trigger id=${triggered.id}" }, config)
        var standing = crossing
        var saleAttempted = false
        for (observation in later) {
            if (window.passedBy(observation)) return deadlinePassed(triggered, standing, window, saleAttempted, config)
            if (observation.curveComplete) return CloseOutcome.Unavailable(triggered, RejectionReason.MIGRATED)
            if (!eligible(observation, config)) continue
            if (observation.slot < window.saleSlot) {
                standing = observation
                continue
            }
            saleAttempted = true
            val proceeds = sale(triggered, observation) ?: continue
            return closed(triggered, observation, observation.slot, proceeds, ExitPriceAssumption.NEXT_TRADE, config)
        }
        return if (window.passedBy(recorded)) deadlinePassed(triggered, standing, window, saleAttempted, config)
        else CloseOutcome.Unavailable(triggered, RejectionReason.HORIZON_REACHED)
    }

    /** Spec P11: `min(fill + maxHoldMs, t0 + hardExitStartMs)`; none without the clocks, or without t0 to count from. */
    private fun exitStart(intent: TradeIntent, fill: PaperFill, source: MarketDataSource, config: SimulationConfig): Instant? {
        val maxHoldMs = config.maxHoldMs ?: return null
        val hardExitStartMs = config.hardExitStartMs ?: return null
        val t0 = intent.t0 ?: return null
        return minOf(fillTrade(fill, intent.pair, source).observedTime.plusMillis(maxHoldMs), t0.plusMillis(hardExitStartMs))
    }

    private fun fillTrade(fill: PaperFill, pair: MarketPair, source: MarketDataSource): MarketObservation =
        checkNotNull(source.observations(pair, fill.actualSlot, fill.actualSlot).firstOrNull { it.id == fill.observationId }) {
            "Missing fill trade orderId=${fill.orderId} observationId=${fill.observationId}"
        }

    /**
     * The curve standing once the fill's own slot ends: its last eligible trade after the fill trade in canonical order, or the
     * fill trade itself; null when the curve completed later in that slot. Exit triggers are still only read from later slots.
     */
    private fun standingAfterFillSlot(position: Position, source: MarketDataSource, config: SimulationConfig): MarketObservation? {
        val fill = position.fill
        val slot = source.observations(position.pair, fill.actualSlot, fill.actualSlot)
        val fillIndex = slot.indexOfFirst { it.id == fill.observationId }
        check(fillIndex >= 0) { "Missing fill trade orderId=${fill.orderId} observationId=${fill.observationId}" }
        var standing = slot[fillIndex]
        for (observation in slot.subList(fillIndex + 1, slot.size)) {
            if (observation.curveComplete) return null
            if (eligible(observation, config)) standing = observation
        }
        return standing
    }

    private fun eligible(observation: MarketObservation, config: SimulationConfig): Boolean =
        ObservationEligibility.issue(observation, config.maxAgeSlots) == null

    private fun crossing(position: Position, observation: MarketObservation): ExitTrigger? = when {
        observation.price >= position.takeProfitPrice -> ExitTrigger(observation.id, observation.slot, observation.observedTime, CloseReason.TAKE_PROFIT)
        observation.price <= position.stopLossPrice -> ExitTrigger(observation.id, observation.slot, observation.observedTime, CloseReason.STOP_LOSS)
        else -> null
    }

    /** [provenAt] is the first slot the recording proves at or after [exitStart]; [standing] is the curve standing at it. */
    private fun timeExit(position: Position, standing: MarketObservation, provenAt: Slot, exitStart: Instant, config: SimulationConfig): CloseOutcome =
        standingSale(position.copy(exitTrigger = ExitTrigger(standing.id, provenAt, exitStart, CloseReason.TIME_EXIT)), standing, provenAt, config)

    /** No trade can land within the 60 s any more: a sale tried within them failed, or none was made and the standing curve sells. */
    private fun deadlinePassed(
        triggered: Position,
        standing: MarketObservation,
        window: ExitSaleWindow,
        saleAttempted: Boolean,
        config: SimulationConfig,
    ): CloseOutcome =
        if (saleAttempted) CloseOutcome.Unavailable(triggered, RejectionReason.UNRESOLVED)
        else standingSale(triggered, standing, window.saleSlot, config)

    private fun standingSale(triggered: Position, standing: MarketObservation, slot: Slot, config: SimulationConfig): CloseOutcome {
        val sale = sale(triggered, standing) ?: return CloseOutcome.Unavailable(triggered, RejectionReason.UNRESOLVED)
        return closed(triggered, standing, slot, sale, ExitPriceAssumption.STANDING_CURVE, config)
    }

    /** The whole position sold into [curve]'s post-trade curve, or null when that curve's real SOL cannot pay it. */
    private fun sale(position: Position, curve: MarketObservation): CurveTrade? {
        val rawTokens = CurveExecution.rawTokens(position.fill.quantity)
        return BondingCurve.sell(curve.curve.reserves, rawTokens)?.let { CurveExecution.trade(curve.curve, it.lamportsOut, rawTokens) }
    }

    /** A net at or below zero still closes: the loss is real and is never dropped. */
    private fun closed(
        triggered: Position,
        curve: MarketObservation,
        slot: Slot,
        sale: CurveTrade,
        assumption: ExitPriceAssumption,
        config: SimulationConfig,
    ): CloseOutcome {
        val trigger = checkNotNull(triggered.exitTrigger) { "Exit without a trigger id=${triggered.id}" }
        val gross = CurveExecution.sol(sale.lamports)
        val fee = CurveExecution.sol(sale.feeLamports)
        val net = DecimalPolicy.scale(gross.subtract(fee).subtract(config.transactionCostSol))
        val close = PositionClose(slot, curve.id, sale.executionPrice, gross, fee, sale.feeBps, config.transactionCostSol,
            sale.priceImpactBps, net, DecimalPolicy.scale(net.subtract(triggered.fill.costBasis)), trigger.reason, assumption)
        return CloseOutcome.Closed(triggered.copy(state = PositionState.CLOSED, close = close))
    }
}
