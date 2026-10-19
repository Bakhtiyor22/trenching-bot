package com.trenching.bot.console.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.domain.MarkerKind
import com.trenching.bot.console.domain.ObservedMarkerView
import com.trenching.bot.marketdata.ReferenceRates
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationSignature
import com.trenching.bot.marketdata.domain.TotalSupplyValuation
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import com.trenching.bot.walletactivity.domain.WalletTradeObserved
import java.math.BigDecimal
import java.time.Instant

/**
 * The tracked wallet's own trades of the pair that no observation records, as tracked markers. A market window starts at the
 * slot after the first buy it follows, so without these the chart would show every one of the wallet's trades except the first
 * buy each cycle starts from.
 */
object ChartWalletTrades {
    /** [trades] are the wallet's accepted trades; those without a block time cannot be placed on a time axis and are left out. */
    fun unobserved(trades: List<WalletTradeObserved>, observations: List<MarketObservation>, pair: MarketPair?, rates: ReferenceRates?): List<ObservedMarkerView> {
        val observed = observations.mapNotNullTo(HashSet(), ObservationSignature::of)
        return trades.filter { it.pair == pair && it.signature.value !in observed }
            .mapNotNull { trade -> trade.blockTime?.let { marker(trade, it, rates) } }
            .sortedWith(ChartMarkers.ITEM_ORDER)
    }

    private fun marker(trade: WalletTradeObserved, time: Instant, rates: ReferenceRates?): ObservedMarkerView {
        val price = DecimalPolicy.swapPrice(trade.quoteAmount, trade.baseAmount)
        return ObservedMarkerView(MarkerKind.OBSERVED, trade.id, trade.wallet.value, true, TradeSide.valueOf(trade.side.name),
            trade.quoteAmount.raw, trade.baseAmount.raw, price, mcap(trade, price, time, rates), trade.slot.value, time,
            trade.signature.value, trade.source)
    }

    /** Valued like a Pump.fun observation: only a SOL-quoted bonding-curve buy at the protocol's decimals, at its minute's rate. */
    private fun mcap(trade: WalletTradeObserved, price: BigDecimal, time: Instant, rates: ReferenceRates?): BigDecimal? {
        val pumpFun = trade.source == PumpFunLayout.BUY_SOURCE && trade.pair.quote == MintAddress.WRAPPED_SOL &&
            trade.baseAmount.decimals == PumpFunLayout.TOKEN_DECIMALS && trade.quoteAmount.decimals == PumpFunLayout.SOL_AMOUNT_DECIMALS
        if (!pumpFun) return null
        return rates?.rateAt(time)?.let { TotalSupplyValuation.usdt(price, it) }
    }
}
