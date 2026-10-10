package com.trenching.bot.replay.application

import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.replay.domain.ReplayMarketWindowPlan
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.walletactivity.WalletHistoryDecodeResult
import com.trenching.bot.walletactivity.application.WalletNormalizer
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import com.trenching.bot.walletactivity.domain.TradeSide

/**
 * Turns a wallet capture's buys into the windows the dip-entry strategy reads: one per mint, the
 * [SimulationConfig.horizonSlots] after the wallet's first buy of it, `[S + 1, S + horizon]`. Later buys of a mint start
 * no cycle, so they need no window of their own.
 */
class ReplayMarketWindowPlanner(private val normalizer: WalletNormalizer = WalletNormalizer()) {
    fun plan(history: WalletHistoryDecodeResult, config: SimulationConfig = SimulationConfig.mvp()): ReplayMarketWindowPlan {
        val parameters = MarketCaptureWindowParameters(horizonSlots = config.horizonSlots, maxAgeSlots = config.maxAgeSlots)
        val buys = history.transactions.map { normalizer.normalize(it, history.wallet, config.quoteMint) }
            .filterIsInstance<NormalizationOutcome.Accepted>()
            .map { it.trade }
            .filter { it.side == TradeSide.BUY }
        val windows = buys.sortedWith(compareBy({ it.slot.value }, { it.signature.value }, { it.instructionIndex }))
            .groupBy { it.pair.base }
            .map { (mint, mintBuys) ->
                val first = mintBuys.first()
                MarketCaptureWindow.of(mint, first.slot.plus(1), first.slot.plus(config.horizonSlots), first.signature)
            }
        return ReplayMarketWindowPlan(windows, parameters, buys.size)
    }
}
