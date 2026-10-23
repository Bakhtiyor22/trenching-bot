package com.trenching.bot.support

import com.trenching.bot.common.MarketPair
import com.trenching.bot.replay.domain.MarketCoverage
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import java.time.Instant
import tools.jackson.databind.json.JsonMapper

/**
 * The v5 twin as schema v6: the same coverage slots, each entry with the `throughTime` [throughTimes] gives its pair, and none
 * for the others. [throughSlots] moves an entry's end as in [DipSampleV5].
 */
object DipSampleV6 {
    fun dataset(throughTimes: Map<MarketPair, Instant> = emptyMap(), throughSlots: Map<MarketPair, Long> = emptyMap()): ReplayDataset {
        val v5 = DipSampleV5.dataset(throughSlots)
        val coverage = v5.coverage.map { MarketCoverage.of(it.pair, it.fromSlot, it.throughSlot, throughTimes[it.pair]) }
        return v5.copy(schemaVersion = ReplayDataset.SCHEMA_V6, coverage = coverage)
    }

    fun json(dataset: ReplayDataset = dataset()): String = ReplayDatasetWriter(JsonMapper.builder().build()).write(dataset)
}
