package com.trenching.bot.replay.application

import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.replay.domain.DatasetEntry
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import com.trenching.bot.replay.mapper.ReplayDatasetMapper
import com.trenching.bot.walletactivity.domain.OrderedWalletTransactions
import java.time.Instant
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class DatasetEntryFactory(private val mapper: ReplayDatasetMapper, json: ObjectMapper) {
    private val writer = ReplayDatasetWriter(json)

    /** Counts and slot range describe the deduplicated dataset, as stored in the snapshot. */
    fun entry(name: String?, dataset: ReplayDataset, createdAt: Instant): DatasetEntry {
        val id = DatasetFingerprint.checksum(dataset)
        val transactions = OrderedWalletTransactions.canonical(dataset.transactions)
        val observations = OrderedMarketData.canonical(dataset.observations)
        val slots = transactions.map { it.slot } + observations.map { it.slot }
        val pairs = (transactions.map { it.pair } + observations.map { it.pair }).distinct()
            .sortedWith(compareBy({ it.base.value }, { it.quote.value }))
        val summary = mapper.summary(id, name ?: defaultName(id), dataset.trackedWallet, dataset.schemaVersion,
            transactions.size, observations.size, slots.minOrNull(), slots.maxOrNull(), pairs, createdAt)
        return mapper.entry(summary, writer.write(dataset))
    }

    private fun defaultName(id: String): String = DEFAULT_NAME_PREFIX + id.take(DEFAULT_NAME_CHECKSUM_CHARS)

    private companion object {
        const val DEFAULT_NAME_PREFIX = "dataset-"
        const val DEFAULT_NAME_CHECKSUM_CHARS = 12
    }
}
