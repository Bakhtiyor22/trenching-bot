package com.trenching.bot.strategy.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.MarketDataSource
import com.trenching.bot.marketdata.ReferenceRates
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationEligibility
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import com.trenching.bot.strategy.domain.*
import com.trenching.bot.walletactivity.domain.TradeSide
import com.trenching.bot.walletactivity.domain.WalletTradeObserved
import java.math.BigDecimal

/**
 * WAIT_FIRST_BUY and WAIT_DIP of the dip-entry strategy. The tracked wallet's first buy of a mint sets P0 and must be
 * valued above the minimum market cap. The first eligible trade in a later slot within the horizon and the [DipWindow], at
 * or below the dip level and before the bonding curve completes, triggers the entry, whatever the market cap there. Prices are
 * compared by [SimulationConfig.priceMeasure]. Under [SimulationConfig.followWalletSells] a sell of the mint by the buy's
 * wallet in the watched slots, from the buy's own through the trigger's, ends the cycle with no entry; inside a slot it is
 * read before any trigger, since the order of a slot's trades is not known.
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
        val referenceObservation = when (config.priceMeasure) {
            PriceMeasure.TRADE_AVERAGE -> null
            PriceMeasure.POST_TRADE -> buyObservation(buy, source, config)
        }
        val referencePrice = referenceObservation?.let(config.priceMeasure::of) ?: DecimalPolicy.swapPrice(buy.quoteAmount, buy.baseAmount)
        val dipPrice = DecimalPolicy.price(referencePrice.multiply(BigDecimal.ONE.subtract(DecimalPolicy.fraction(config.dipBps))))
        val horizonEnd = buy.slot.plus(config.horizonSlots)
        val window = DipWindow.of(config, buy.blockTime)
        val result = when {
            !supported(buy) -> DipWatchResult(DipWatchOutcome.UNSUPPORTED_SUPPLY)
            config.priceMeasure == PriceMeasure.POST_TRADE && referenceObservation == null -> DipWatchResult(DipWatchOutcome.REFERENCE_UNAVAILABLE)
            belowMinMarketCap(buy, referencePrice, horizonEnd, config, source, rates) -> DipWatchResult(DipWatchOutcome.REFERENCE_BELOW_MIN_MARKET_CAP)
            window is DipWindow.Closed -> DipWatchResult(DipWatchOutcome.NO_DIP)
            else -> watch(buy, dipPrice, horizonEnd, window, config, source, rates)
        }
        val trigger = result.trigger
        return TradeIntent("$runId:${buy.id}", runId, buy.id, buy.wallet, buy.pair, config.strategyName, config.strategyVersion,
            configHash, ENTRY_OFFSET, buy.slot, buy.blockTime, trigger?.slot ?: buy.slot.plus(1), config.notional, referencePrice,
            dipPrice, horizonEnd, result.outcome, trigger, referenceObservation?.id, result.walletSell)
    }

    /**
     * Exactly one observation of the buy's slot records the buy, and as a price it must be eligible; otherwise none is used
     * (`docs/adr/20261025-post-trade-price-and-wallet-sell.md`, Decision 2).
     */
    private fun buyObservation(buy: WalletTradeObserved, source: MarketDataSource, config: SimulationConfig): MarketObservation? =
        source.observations(buy.pair, buy.slot, buy.slot).filter { WalletObservations.recordsBuy(it, buy) }.singleOrNull()
            ?.takeIf { ObservationEligibility.issue(it, config.maxAgeSlots) == null }

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
        window: DipWindow,
        config: SimulationConfig,
        source: MarketDataSource,
        rates: ReferenceRates,
    ): DipWatchResult {
        for ((slot, slotObservations) in source.observations(buy.pair, buy.slot, horizonEnd).groupBy { it.slot }) {
            walletSell(slotObservations, buy, window, config)?.let { return DipWatchResult(DipWatchOutcome.WALLET_SOLD, walletSell = it) }
            if (slot == buy.slot) continue
            for (observation in slotObservations) {
                if (!window.admits(observation.observedTime)) return DipWatchResult(DipWatchOutcome.NO_DIP)
                if (observation.curveComplete) return DipWatchResult(DipWatchOutcome.MIGRATED_BEFORE_ENTRY)
                val price = config.priceMeasure.of(observation)
                if (ObservationEligibility.issue(observation, config.maxAgeSlots) != null || price > dipPrice) continue
                val rate = checkNotNull(rates.rateAt(observation.observedTime)) {
                    "Dataset has no reference rate for observation id=${observation.id}"
                }
                return DipWatchResult(DipWatchOutcome.TRIGGERED,
                    DipTrigger(observation.id, observation.slot, DecimalPolicy.price(price), marketCap(price, rate), DecimalPolicy.price(rate)))
            }
        }
        return DipWatchResult(DipWatchOutcome.NO_DIP)
    }

    /** The first sell of [slotObservations] by the buy's wallet that the dip window still admits, when [config] follows them. */
    private fun walletSell(
        slotObservations: List<MarketObservation>,
        buy: WalletTradeObserved,
        window: DipWindow,
        config: SimulationConfig,
    ): WalletSellSignal? {
        if (!config.followWalletSells) return null
        return slotObservations.firstOrNull { WalletObservations.isSellBy(it, buy.wallet) && window.admits(it.observedTime) }
            ?.let { WalletSellSignal(it.id, it.slot) }
    }

    private fun marketCap(price: BigDecimal, rate: BigDecimal): BigDecimal = DecimalPolicy.scale(price.multiply(SUPPLY).multiply(rate))

    companion object {
        /** One cycle per mint, so the scenario key's entry offset is always 0. */
        const val ENTRY_OFFSET = 0
        private val SUPPLY: BigDecimal = BigDecimal.valueOf(PumpFunLayout.TOKEN_SUPPLY)
    }
}
