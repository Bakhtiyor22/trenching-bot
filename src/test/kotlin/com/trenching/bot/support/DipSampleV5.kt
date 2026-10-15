package com.trenching.bot.support

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.Slot
import com.trenching.bot.replay.domain.MarketCoverage
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import com.trenching.bot.strategy.domain.SimulationConfig
import tools.jackson.databind.json.JsonMapper

/**
 * The v4 twin as schema v5: each mint's coverage is its full window `[S+1, S+4500]` after the wallet's first buy S, unless
 * [dataset] is given another `throughSlot` for its pair.
 */
object DipSampleV5 {
    fun dataset(throughSlots: Map<MarketPair, Long> = emptyMap()): ReplayDataset {
        val v4 = DipSampleV4.dataset()
        val firstBuys = v4.transactions.groupBy { it.pair }.mapValues { (_, buys) -> buys.minOf { it.slot } }
        val coverage = firstBuys.map { (pair, buySlot) ->
            MarketCoverage.of(pair, buySlot.plus(1), throughSlots[pair]?.let(Slot::of) ?: buySlot.plus(SimulationConfig.mvp().horizonSlots))
        }
        return v4.copy(schemaVersion = ReplayDataset.SCHEMA_V5, coverage = coverage)
    }

    fun json(dataset: ReplayDataset = dataset()): String = ReplayDatasetWriter(JsonMapper.builder().build()).write(dataset)
}
