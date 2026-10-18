package com.trenching.bot.console.application

import com.trenching.bot.console.capture.CaptureSourceLookup
import com.trenching.bot.console.domain.DatasetSource
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.domain.DatasetSummary

/** Names the dataset a run or dataset view shows and the capture that exported it; [captures] is absent without the capture console. */
class DatasetSourceService(
    private val catalog: ReplayDatasetCatalog,
    private val captures: CaptureSourceLookup,
) {
    fun of(datasetId: String): DatasetSource = DatasetSource(datasetId, catalog.find(datasetId)?.name, captures.find(datasetId))

    fun of(dataset: DatasetSummary): DatasetSource = DatasetSource(dataset.id, dataset.name, captures.find(dataset.id))
}
