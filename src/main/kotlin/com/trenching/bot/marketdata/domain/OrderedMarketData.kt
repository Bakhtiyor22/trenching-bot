package com.trenching.bot.marketdata.domain

import com.trenching.bot.common.*
import com.trenching.bot.marketdata.MarketDataSource

class OrderedMarketData(observations: List<MarketObservation>) : MarketDataSource {
    private val byPair: Map<MarketPair, List<MarketObservation>> = canonical(observations).groupBy { it.pair }

    override fun observations(pair: MarketPair, from: Slot, through: Slot): List<MarketObservation> {
        val ordered = byPair[pair] ?: return emptyList()
        return ordered.subList(firstIndex(ordered) { it.slot >= from }, firstIndex(ordered) { it.slot > through })
    }

    /** Binary search for the first observation matching [reached]; `ordered` is slot-sorted per pair. */
    private fun firstIndex(ordered: List<MarketObservation>, reached: (MarketObservation) -> Boolean): Int {
        var low = 0
        var high = ordered.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (reached(ordered[middle])) high = middle else low = middle + 1
        }
        return low
    }

    companion object {
        fun canonical(observations: List<MarketObservation>): List<MarketObservation> =
            observations.groupBy { it.id }.map { (id, duplicates) ->
                require(duplicates.distinct().size == 1) { "Conflicting observation id=$id" }
                duplicates.first()
            }.sortedWith(compareBy({ it.slot.value }, { it.id }))
    }
}
