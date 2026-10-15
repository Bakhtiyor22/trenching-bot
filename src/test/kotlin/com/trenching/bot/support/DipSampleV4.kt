package com.trenching.bot.support

import com.trenching.bot.common.Base58
import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import java.math.BigInteger
import tools.jackson.databind.json.JsonMapper

/** The bundled v3 sample turned into a schema v4 twin: the same observations, each with a synthetic, valid trade. */
object DipSampleV4 {
    private val TRADERS = listOf(1, 2, 3).map { Base58.encode(ByteArray(32) { _ -> it.toByte() }) }

    fun trade(index: Int): ObservedTrade = ObservedTrade.of(
        TRADERS[index % TRADERS.size], if (index % 2 == 0) TradeSide.BUY else TradeSide.SELL,
        BigInteger.valueOf(1_000_000L + index), BigInteger.valueOf(10_000_000_000L + index))

    fun dataset(): ReplayDataset {
        val v3 = DipSample.load()
        return v3.copy(schemaVersion = ReplayDataset.SCHEMA_V4,
            observations = v3.observations.mapIndexed { index, observation -> observation.copy(trade = trade(index)) })
    }

    /** The twin as the writer emits it, with its checksum recomputed for schema version 4. */
    fun json(): String = ReplayDatasetWriter(JsonMapper.builder().build()).write(dataset())
}
