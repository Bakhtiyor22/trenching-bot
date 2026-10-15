package com.trenching.bot.replay.domain

import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservedTrade

/** The refusal codes of a dataset's trade fields, and the one place a v4 observation's required trade is read. */
internal object DatasetTrades {
    const val FIELDS_MISSING = "TRADE_FIELDS_MISSING"
    const val FIELDS_IN_V3 = "TRADE_FIELDS_IN_V3"
    const val FIELDS_INVALID = "TRADE_FIELDS_INVALID"

    fun allPresent(observations: List<MarketObservation>): Boolean = observations.all { it.trade != null }

    fun required(observation: MarketObservation): ObservedTrade =
        requireNotNull(observation.trade) { "Observation without trade id=${observation.id} in a schema v4 dataset ($FIELDS_MISSING)" }
}
