package com.trenching.bot.console.application

import com.trenching.bot.console.domain.RunDetailView
import com.trenching.bot.replay.ReplayDatasetCatalog
import org.springframework.stereotype.Service

/** The run page's detail: the run with the dataset, capture and case it came from. */
@Service
class RunDetailService(
    private val consoleService: ConsoleService,
    private val catalog: ReplayDatasetCatalog,
    private val sources: DatasetSourceService,
) {
    fun detail(runId: String): RunDetailView {
        val detail = consoleService.detail(runId)
        val datasetId = detail.run.datasetId
        return RunDetailView(detail, sources.of(datasetId, catalog.find(datasetId)?.name))
    }
}
