package com.trenching.bot.replay.application

import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.SimulationRunRecord
import com.trenching.bot.strategy.domain.SimulationConfig
import org.springframework.stereotype.Service

@Service
class PersistedReplayService(private val ledger: SimulationRunLedger) {
    fun execute(dataset: ReplayDataset, config: SimulationConfig): SimulationRunRecord =
        ledger.persist(dataset, ReplayRunner().run(dataset, config))
}
