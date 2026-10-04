package com.trenching.bot.marketdata.infrastructure.extraction

import com.trenching.bot.marketdata.MarketObservationExclusion
import com.trenching.bot.marketdata.domain.MarketObservation

internal sealed interface TradeExtraction {
    data class Observed(val observation: MarketObservation) : TradeExtraction
    data class Excluded(val reason: MarketObservationExclusion) : TradeExtraction
}
