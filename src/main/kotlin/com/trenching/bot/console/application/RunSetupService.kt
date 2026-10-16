package com.trenching.bot.console.application

import com.trenching.bot.console.domain.AgreedStrategyPreset
import com.trenching.bot.console.domain.InvalidConsoleRequestException
import com.trenching.bot.console.domain.Recommendation
import com.trenching.bot.console.domain.RunPreset
import com.trenching.bot.console.domain.RunRecommendations
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.domain.DatasetNotFoundException
import org.springframework.stereotype.Service

/** What the new-run form starts from: the agreed-strategy preset and recommendations for one stored dataset. */
@Service
class RunSetupService(private val catalog: ReplayDatasetCatalog) {
    fun preset(): RunPreset = AgreedStrategyPreset.PRESET

    fun recommendations(datasetId: String): List<Recommendation> {
        val id = datasetId.trim().takeIf { it.isNotEmpty() } ?: throw InvalidConsoleRequestException(DATASET_ID, "is required")
        val dataset = catalog.find(id) ?: throw DatasetNotFoundException(id)
        return RunRecommendations.of(dataset.pairs.size)
    }

    private companion object {
        const val DATASET_ID = "datasetId"
    }
}
