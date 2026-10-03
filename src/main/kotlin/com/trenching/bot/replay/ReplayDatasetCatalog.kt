package com.trenching.bot.replay

import com.trenching.bot.common.PageResult
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.SampleDataset
import java.io.InputStream

/** Stored replay datasets, identified by checksum. Lists are newest first; page size is capped at 100. */
interface ReplayDatasetCatalog {
    /** Idempotent by checksum: identical content under another name returns the existing dataset. */
    fun register(name: String, json: InputStream): DatasetRegistration
    fun importSample(sample: SampleDataset): DatasetRegistration
    fun page(page: Int, size: Int): PageResult<DatasetSummary>
    fun find(id: String): DatasetSummary?
    fun load(id: String): ReplayDataset
}
