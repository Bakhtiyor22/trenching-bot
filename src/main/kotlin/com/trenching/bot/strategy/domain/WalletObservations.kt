package com.trenching.bot.strategy.domain

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationSignature
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.walletactivity.domain.WalletTradeObserved

/** How the tracked wallet's own trades are recognised in the market data, per `docs/adr/20261025-post-trade-price-and-wallet-sell.md`. */
object WalletObservations {
    /** Decision 4: a sell is read from the market data only, by the trade's signer; whatever its amount or eligibility. */
    fun isSellBy(observation: MarketObservation, wallet: WalletAddress): Boolean =
        observation.trade?.let { it.trader == wallet.value && it.side == TradeSide.SELL } ?: false

    /**
     * Decision 2: [observation] records [buy] when it names the buy's transaction and top-level instruction and, when its
     * trade was recorded, that trade is a buy signed by the buy's wallet. Data recorded before trades were kept matches on the
     * transaction alone.
     */
    fun recordsBuy(observation: MarketObservation, buy: WalletTradeObserved): Boolean =
        observation.pair == buy.pair && observation.slot == buy.slot &&
            ObservationSignature.of(observation) == buy.signature.value &&
            ObservationSignature.topLevelIndex(observation) == buy.instructionIndex &&
            (observation.trade?.let { it.trader == buy.wallet.value && it.side == TradeSide.BUY } ?: true)
}
