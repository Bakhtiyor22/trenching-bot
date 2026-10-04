package com.trenching.bot.replay.application

import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.replay.domain.ReplayMarketWindowPlan
import com.trenching.bot.strategy.application.CopyBuyStrategy
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.walletactivity.WalletHistoryDecodeResult
import com.trenching.bot.walletactivity.application.WalletNormalizer
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import com.trenching.bot.walletactivity.domain.TradeSide
import com.trenching.bot.walletactivity.domain.WalletTradeObserved

/**
 * Turns the buys the strategy accepts into the slot windows their entries and exits can use. An entry at source slot
 * S + offset fills up to [SimulationConfig.maxWaitSlots] later, and its exit waits [SimulationConfig.holdingSlots] from
 * that fill plus the same wait, so a buy needs [S + first offset, S + last offset + wait + holding + wait].
 */
class ReplayMarketWindowPlanner(private val normalizer: WalletNormalizer = WalletNormalizer()) {
    fun plan(history: WalletHistoryDecodeResult, config: SimulationConfig = SimulationConfig.mvp()): ReplayMarketWindowPlan {
        val parameters = MarketCaptureWindowParameters(
            firstEntryOffset = CopyBuyStrategy.ENTRY_OFFSETS.first.toLong(),
            lastEntryOffset = CopyBuyStrategy.ENTRY_OFFSETS.last.toLong(),
            maxWaitSlots = config.maxWaitSlots,
            maxAgeSlots = config.maxAgeSlots,
            holdingSlots = config.holdingSlots,
        )
        val buys = history.transactions.map { normalizer.normalize(it, history.wallet, config.quoteMint) }
            .filterIsInstance<NormalizationOutcome.Accepted>()
            .map { it.trade }
            .filter { it.side == TradeSide.BUY }
        val windows = buys.sortedWith(compareBy({ it.pair.base.value }, { it.slot.value }, { it.signature.value }))
            .map { window(it, parameters) }
            .fold(emptyList<MarketCaptureWindow>(), ::merge)
        return ReplayMarketWindowPlan(windows, parameters, buys.size)
    }

    private fun window(buy: WalletTradeObserved, parameters: MarketCaptureWindowParameters): MarketCaptureWindow {
        val lastExitSlot = buy.slot.plus(parameters.lastEntryOffset).plus(parameters.maxWaitSlots)
            .plus(parameters.holdingSlots).plus(parameters.maxWaitSlots)
        return MarketCaptureWindow.of(buy.pair.base, buy.slot.plus(parameters.firstEntryOffset), lastExitSlot, buy.signature)
    }

    /** Buys arrive oldest first per mint, so a merged window keeps the oldest buy as its listing bound. */
    private fun merge(windows: List<MarketCaptureWindow>, next: MarketCaptureWindow): List<MarketCaptureWindow> {
        val previous = windows.lastOrNull()
        if (previous == null || previous.baseMint != next.baseMint || next.fromSlot.value > Math.addExact(previous.toSlot.value, 1)) {
            return windows + next
        }
        val merged = MarketCaptureWindow.of(previous.baseMint, previous.fromSlot, maxOf(previous.toSlot, next.toSlot), previous.untilSignature)
        return windows.dropLast(1) + merged
    }
}
