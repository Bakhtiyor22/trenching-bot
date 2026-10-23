package com.trenching.bot.replay.application

import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.replay.domain.ReplayMarketWindowPlan
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.walletactivity.WalletHistoryDecodeResult
import com.trenching.bot.walletactivity.application.WalletNormalizer
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import com.trenching.bot.walletactivity.domain.TradeSide
import com.trenching.bot.walletactivity.domain.WalletTradeObserved

/**
 * Turns a wallet capture's buys into the windows the dip-entry strategy reads: one per mint, from [leadSlots] before the
 * wallet's first buy S of it through the [SimulationConfig.horizonSlots] after it, `[S - lead, S + horizon]`. Later buys of a
 * mint start no cycle, so they need no window of their own. A new capture takes the default lead; an existing one is planned
 * again with the lead it recorded.
 */
class ReplayMarketWindowPlanner(private val normalizer: WalletNormalizer = WalletNormalizer()) {
    fun plan(
        history: WalletHistoryDecodeResult,
        config: SimulationConfig = SimulationConfig.mvp(),
        leadSlots: Long = MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS,
    ): ReplayMarketWindowPlan {
        val parameters = parameters(config, leadSlots)
        val buys = buys(history, config)
        val windows = firstBuys(buys).map { first -> MarketCaptureWindow.forFirstBuy(first.pair.base, first.slot, first.signature, parameters) }
        return ReplayMarketWindowPlan(windows, parameters, buys.size)
    }

    /** Whether every mint's first buy leaves room for [leadSlots] before it, so [plan] can place each window from slot 0 on. */
    fun leadFits(
        history: WalletHistoryDecodeResult,
        config: SimulationConfig = SimulationConfig.mvp(),
        leadSlots: Long = MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS,
    ): Boolean {
        val parameters = parameters(config, leadSlots)
        return firstBuys(buys(history, config)).all { MarketCaptureWindow.leadFits(it.slot, parameters) }
    }

    private fun parameters(config: SimulationConfig, leadSlots: Long) =
        MarketCaptureWindowParameters(config.horizonSlots, config.maxAgeSlots, leadSlots)

    private fun buys(history: WalletHistoryDecodeResult, config: SimulationConfig): List<WalletTradeObserved> =
        history.transactions.map { normalizer.normalize(it, history.wallet, config.quoteMint) }
            .filterIsInstance<NormalizationOutcome.Accepted>()
            .map { it.trade }
            .filter { it.side == TradeSide.BUY }

    private fun firstBuys(buys: List<WalletTradeObserved>): List<WalletTradeObserved> =
        buys.sortedWith(compareBy({ it.slot.value }, { it.signature.value }, { it.instructionIndex }))
            .groupBy { it.pair.base }
            .map { (_, mintBuys) -> mintBuys.first() }
}
