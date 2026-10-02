package com.trenching.bot.marketdata

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.MarketObservation

interface MarketDataSource {
    fun observations(pair: MarketPair, from: Slot, through: Slot): List<MarketObservation>
}
