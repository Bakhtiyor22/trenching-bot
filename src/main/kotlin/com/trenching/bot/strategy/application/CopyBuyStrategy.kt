package com.trenching.bot.strategy.application

import com.trenching.bot.common.ReplayLimits
import com.trenching.bot.strategy.domain.*
import com.trenching.bot.walletactivity.domain.*

class CopyBuyStrategy {
    fun intents(trade: WalletTradeObserved, runId: String, configHash: String, config: SimulationConfig): List<TradeIntent> {
        if (trade.side != TradeSide.BUY) return emptyList()
        return ENTRY_OFFSETS.map { offset ->
            TradeIntent("$runId:${trade.id}:$offset", runId, trade.id, trade.pair,
                config.strategyName, config.strategyVersion, configHash, offset, trade.slot,
                trade.slot.plus(offset.toLong()), config.notional)
        }
    }

    companion object {
        val ENTRY_OFFSETS: IntRange = 1..ReplayLimits.MAX_SCENARIOS_PER_TRADE
    }
}
