package com.trenching.bot.papertrading.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.marketdata.MarketDataSource
import com.trenching.bot.marketdata.domain.ObservationEligibility
import com.trenching.bot.papertrading.CurveExecution
import com.trenching.bot.papertrading.domain.*
import com.trenching.bot.protocol.pumpfun.BondingCurve
import com.trenching.bot.strategy.domain.*

/**
 * Fills a triggered dip entry at the first eligible trade at least `latencySlots` after the trigger's slot, within the
 * horizon and before the bonding curve completes. Per the bonding-curve-execution ADR, the notional buys against that
 * trade's post-trade curve and pays its fees plus the transaction cost on top; an entry the curve cannot cover is
 * rejected, never partially filled or moved to a later trade.
 */
class FillModel {
    fun fill(intent: TradeIntent, source: MarketDataSource, config: SimulationConfig): FillOutcome {
        val trigger = when (intent.watch) {
            DipWatchOutcome.TRIGGERED -> checkNotNull(intent.trigger) { "Triggered intent without a trigger id=${intent.id}" }
            DipWatchOutcome.NO_DIP -> return FillOutcome.Rejected(intent.id, RejectionReason.NO_DIP)
            DipWatchOutcome.DIP_BELOW_MIN_MARKET_CAP -> return FillOutcome.Rejected(intent.id, RejectionReason.DIP_BELOW_MIN_MARKET_CAP)
            DipWatchOutcome.MIGRATED_BEFORE_ENTRY -> return FillOutcome.Rejected(intent.id, RejectionReason.MIGRATED_BEFORE_ENTRY)
            DipWatchOutcome.UNSUPPORTED_SUPPLY -> return FillOutcome.Rejected(intent.id, RejectionReason.UNSUPPORTED_SUPPLY)
        }
        val earliestFillSlot = trigger.slot.plus(config.latencySlots)
        val observation = source.observations(intent.pair, trigger.slot, intent.horizonEndSlot).asSequence()
            .takeWhile { !it.curveComplete }
            .firstOrNull { it.slot >= earliestFillSlot && ObservationEligibility.issue(it, config.maxAgeSlots) == null }
            ?: return FillOutcome.Rejected(intent.id, RejectionReason.NO_FILL_AFTER_TRIGGER)
        val lamportsIn = CurveExecution.lamports(intent.notional)
        val bought = BondingCurve.buy(observation.curve.reserves, lamportsIn)
            ?: return FillOutcome.Rejected(intent.id, RejectionReason.EXCEEDS_CURVE)
        if (bought.tokensOut.signum() == 0) return FillOutcome.Rejected(intent.id, RejectionReason.ZERO_QUANTITY)
        val trade = CurveExecution.trade(observation.curve, lamportsIn, bought.tokensOut)
        val fee = CurveExecution.sol(trade.feeLamports)
        return FillOutcome.Filled(PaperFill(intent.id, intent.targetSlot, observation.slot, observation.id,
            trade.executionPrice, CurveExecution.quantity(trade.rawTokens), fee,
            DecimalPolicy.scale(intent.notional.add(fee).add(config.transactionCostSol)),
            trade.feeBps, NO_SLIPPAGE_BPS, observation.source, observation.quality, config.transactionCostSol, trade.priceImpactBps))
    }

    private companion object {
        /** Price impact comes from the curve itself, so no separate slippage is applied. */
        const val NO_SLIPPAGE_BPS = 0
    }
}
