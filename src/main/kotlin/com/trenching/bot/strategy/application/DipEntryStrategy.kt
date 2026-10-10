package com.trenching.bot.strategy.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.MarketDataSource
import com.trenching.bot.marketdata.ReferenceRates
import com.trenching.bot.marketdata.domain.ObservationEligibility
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import com.trenching.bot.strategy.domain.*
import com.trenching.bot.walletactivity.domain.TradeSide
import com.trenching.bot.walletactivity.domain.WalletTradeObserved
import java.math.BigDecimal

/**
 * WAIT_FIRST_BUY and WAIT_DIP of the dip-entry strategy. The tracked wallet's first buy of a mint sets P0. The first
 * eligible trade in a later slot within the horizon, at or below the dip level, with enough market cap and before the
 * bonding curve completes, triggers the entry.
 */
class DipEntryStrategy {
    fun plan(
        trades: List<WalletTradeObserved>,
        runId: String,
        configHash: String,
        config: SimulationConfig,
        source: MarketDataSource,
        rates: ReferenceRates,
    ): DipPlan {
        val buys = trades.filter { it.side == TradeSide.BUY }
            .sortedWith(compareBy({ it.slot.value }, { it.signature.value }, { it.instructionIndex }))
        val firstByPair = buys.groupBy { it.pair }.mapValues { (_, pairBuys) -> pairBuys.first() }
        val first = firstByPair.values.map { it.id }.toSet()
        val intents = firstByPair.values.map { intent(it, runId, configHash, config, source, rates) }
            .sortedWith(compareBy({ it.targetSlot.value }, { it.id }))
        return DipPlan(intents, buys.map { it.id }.filterNot { it in first })
    }

    private fun intent(
        buy: WalletTradeObserved,
        runId: String,
        configHash: String,
        config: SimulationConfig,
        source: MarketDataSource,
        rates: ReferenceRates,
    ): TradeIntent {
        val referencePrice = BigDecimal(buy.quoteAmount.raw, buy.quoteAmount.decimals)
            .divide(BigDecimal(buy.baseAmount.raw, buy.baseAmount.decimals), DecimalPolicy.PRICE_SCALE, DecimalPolicy.ROUNDING)
        val dipPrice = DecimalPolicy.price(referencePrice.multiply(BigDecimal.ONE.subtract(DecimalPolicy.fraction(config.dipBps))))
        val horizonEnd = buy.slot.plus(config.horizonSlots)
        val (watch, trigger) = if (supported(buy)) watch(buy, dipPrice, horizonEnd, config, source, rates)
            else DipWatchOutcome.UNSUPPORTED_SUPPLY to null
        return TradeIntent("$runId:${buy.id}", runId, buy.id, buy.pair, config.strategyName, config.strategyVersion, configHash,
            ENTRY_OFFSET, buy.slot, trigger?.slot ?: buy.slot.plus(1), config.notional, referencePrice, dipPrice, horizonEnd,
            watch, trigger)
    }

    /** Market cap assumes the fixed bonding-curve supply, so only Pump.fun buys with its decimals qualify. */
    private fun supported(buy: WalletTradeObserved): Boolean =
        buy.source == PumpFunLayout.BUY_SOURCE && buy.baseAmount.decimals == PumpFunLayout.TOKEN_DECIMALS

    private fun watch(
        buy: WalletTradeObserved,
        dipPrice: BigDecimal,
        horizonEnd: Slot,
        config: SimulationConfig,
        source: MarketDataSource,
        rates: ReferenceRates,
    ): Pair<DipWatchOutcome, DipTrigger?> {
        var dipBelowMarketCap = false
        for (observation in source.observations(buy.pair, buy.slot.plus(1), horizonEnd)) {
            if (observation.curveComplete) return DipWatchOutcome.MIGRATED_BEFORE_ENTRY to null
            if (ObservationEligibility.issue(observation, config.maxAgeSlots) != null || observation.price > dipPrice) continue
            val rate = checkNotNull(rates.rateAt(observation.observedTime)) {
                "Dataset has no reference rate for observation id=${observation.id}"
            }
            val marketCap = DecimalPolicy.scale(observation.price.multiply(SUPPLY).multiply(rate))
            if (marketCap >= config.minMarketCapUsd) {
                return DipWatchOutcome.TRIGGERED to DipTrigger(observation.id, observation.slot,
                    DecimalPolicy.price(observation.price), marketCap, DecimalPolicy.price(rate))
            }
            dipBelowMarketCap = true
        }
        return (if (dipBelowMarketCap) DipWatchOutcome.DIP_BELOW_MIN_MARKET_CAP else DipWatchOutcome.NO_DIP) to null
    }

    companion object {
        /** One cycle per mint, so the scenario key's entry offset is always 0. */
        const val ENTRY_OFFSET = 0
        private val SUPPLY: BigDecimal = BigDecimal.valueOf(PumpFunLayout.TOKEN_SUPPLY)
    }
}
