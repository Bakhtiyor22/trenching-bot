package com.trenching.bot.strategy

import com.trenching.bot.strategy.domain.TradeIntent

interface TradeIntentStore {
    fun record(intents: List<TradeIntent>)
    fun intents(runId: String): List<TradeIntent>
}
