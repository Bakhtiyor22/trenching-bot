package com.trenching.bot.support

import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.replay.domain.ReplayDataset
import java.math.BigInteger

/**
 * The agreed sample with the tracked wallet selling the take-profit mint before its dip and the stop-loss mint while held, and
 * the horizon mint's buy left out of the market data. Under `SimulationConfig.agreed()` those cycles end `WALLET_SOLD` and
 * `REFERENCE_UNAVAILABLE`, and the stop-loss position closes `WALLET_SOLD`.
 */
object WalletSellDipSample {
    const val TAKE_PROFIT_SELL = "fixture:TP:1001:0"
    const val STOP_LOSS_SELL = "fixture:SL:2003:0"
    private val SELLS = setOf(TAKE_PROFIT_SELL, STOP_LOSS_SELL)

    fun dataset(): ReplayDataset {
        val agreed = AgreedDipSample.dataset()
        val wallet = agreed.trackedWallet.value
        val horizon = DipSample.pair(DipSample.HORIZON)
        return agreed.copy(observations = agreed.observations
            .filterNot { it.pair == horizon && it.trade?.trader == wallet }
            .map { if (it.id in SELLS) it.copy(trade = ObservedTrade.of(wallet, TradeSide.SELL, BigInteger.ONE, BigInteger.ONE)) else it })
    }
}
