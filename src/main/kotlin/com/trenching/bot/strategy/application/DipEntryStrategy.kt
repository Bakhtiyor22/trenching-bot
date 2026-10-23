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
 * WAIT_FIRST_BUY and WAIT_DIP of the dip-entry strategy. The tracked wallet's first buy of a mint sets P0 and must be
 * valued above the minimum market cap. The first eligible trade in a later slot within the horizon, at or below the dip
 * level and before the bonding curve completes, triggers the entry, whatever the market cap there.
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
        val referencePrice = DecimalPolicy.swapPrice(buy.quoteAmount, buy.baseAmount)
        val dipPrice = DecimalPolicy.price(referencePrice.multiply(BigDecimal.ONE.subtract(DecimalPolicy.fraction(config.dipBps))))
        val horizonEnd = buy.slot.plus(config.horizonSlots)
        val (watch, trigger) = when {
            !supported(buy) -> DipWatchOutcome.UNSUPPORTED_SUPPLY to null
            belowMinMarketCap(buy, referencePrice, horizonEnd, config, source, rates) -> DipWatchOutcome.REFERENCE_BELOW_MIN_MARKET_CAP to null
            else -> watch(buy, dipPrice, horizonEnd, config, source, rates)
        }
        return TradeIntent("$runId:${buy.id}", runId, buy.id, buy.pair, config.strategyName, config.strategyVersion, configHash,
            ENTRY_OFFSET, buy.slot, trigger?.slot ?: buy.slot.plus(1), config.notional, referencePrice, dipPrice, horizonEnd,
            watch, trigger)
    }

    /** Market cap assumes the fixed bonding-curve supply, so only Pump.fun buys with its decimals qualify. */
    private fun supported(buy: WalletTradeObserved): Boolean =
        buy.source == PumpFunLayout.BUY_SOURCE && buy.baseAmount.decimals == PumpFunLayout.TOKEN_DECIMALS

    /**
     * The spec's reference check: the buy's market cap must be strictly above the minimum. It is valued at the rate of the
     * buy's minute; a dataset recorded before the market-window lead may lack that minute, then the rate of the token's next
     * recorded trade is used. Without either the buy cannot be valued yet and is let through, since no trade can trigger it.
     */
    private fun belowMinMarketCap(
        buy: WalletTradeObserved,
        referencePrice: BigDecimal,
        horizonEnd: Slot,
        config: SimulationConfig,
        source: MarketDataSource,
        rates: ReferenceRates,
    ): Boolean {
        val rate = buy.blockTime?.let(rates::rateAt)
            ?: source.observations(buy.pair, buy.slot.plus(1), horizonEnd).firstOrNull()?.let { rates.rateAt(it.observedTime) }
            ?: return false
        return marketCap(referencePrice, rate) <= config.minMarketCapUsd
    }

    private fun watch(
        buy: WalletTradeObserved,
        dipPrice: BigDecimal,
        horizonEnd: Slot,
        config: SimulationConfig,
        source: MarketDataSource,
        rates: ReferenceRates,
    ): Pair<DipWatchOutcome, DipTrigger?> {
        for (observation in source.observations(buy.pair, buy.slot.plus(1), horizonEnd)) {
            if (observation.curveComplete) return DipWatchOutcome.MIGRATED_BEFORE_ENTRY to null
            if (ObservationEligibility.issue(observation, config.maxAgeSlots) != null || observation.price > dipPrice) continue
            val rate = checkNotNull(rates.rateAt(observation.observedTime)) {
                "Dataset has no reference rate for observation id=${observation.id}"
            }
            return DipWatchOutcome.TRIGGERED to DipTrigger(observation.id, observation.slot, DecimalPolicy.price(observation.price),
                marketCap(observation.price, rate), DecimalPolicy.price(rate))
        }
        return DipWatchOutcome.NO_DIP to null
    }

    private fun marketCap(price: BigDecimal, rate: BigDecimal): BigDecimal = DecimalPolicy.scale(price.multiply(SUPPLY).multiply(rate))

    companion object {
        /** One cycle per mint, so the scenario key's entry offset is always 0. */
        const val ENTRY_OFFSET = 0
        private val SUPPLY: BigDecimal = BigDecimal.valueOf(PumpFunLayout.TOKEN_SUPPLY)
    }
}
