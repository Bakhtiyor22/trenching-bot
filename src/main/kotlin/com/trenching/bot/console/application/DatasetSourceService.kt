package com.trenching.bot.console.application

import com.trenching.bot.console.domain.CaptureSources
import com.trenching.bot.console.domain.DatasetSource
import org.springframework.stereotype.Service

/** Links a dataset back to the capture that registered it; [captures] is absent when the local capture console is off. */
@Service
class DatasetSourceService(private val captures: CaptureSources?) {
    fun of(datasetId: String, datasetName: String?): DatasetSource {
        val capture = captures?.forDataset(datasetId)
        return DatasetSource(datasetId, datasetName, capture?.captureId, capture?.displayName, capture?.caseId)
    }
}
