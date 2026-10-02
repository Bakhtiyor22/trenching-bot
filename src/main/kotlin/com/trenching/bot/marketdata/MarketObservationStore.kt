package com.trenching.bot.marketdata

import com.trenching.bot.marketdata.domain.MarketObservation

interface MarketObservationStore {
    fun record(runId: String, observations: List<MarketObservation>)
    fun observations(runId: String): List<MarketObservation>
}
