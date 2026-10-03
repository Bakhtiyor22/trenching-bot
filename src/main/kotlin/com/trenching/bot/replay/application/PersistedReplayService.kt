package com.trenching.bot.replay.application

import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.SimulationRunRecord
import com.trenching.bot.strategy.domain.SimulationConfig
import java.io.InputStream
import org.springframework.stereotype.Service

@Service
class PersistedReplayService(
    private val ledger: SimulationRunLedger,
    private val catalog: ReplayDatasetCatalog,
) {
    fun execute(dataset: ReplayDataset, config: SimulationConfig): SimulationRunRecord =
        ledger.persist(dataset, ReplayRunner().run(dataset, config))

    /** Registers the dataset under [name] before running it, so the catalog lists it with that label. */
    fun execute(name: String, json: InputStream, config: SimulationConfig): SimulationRunRecord =
        execute(catalog.load(catalog.register(name, json).dataset.id), config)
}
