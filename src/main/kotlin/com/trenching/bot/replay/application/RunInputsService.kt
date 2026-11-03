package com.trenching.bot.replay.application

import com.trenching.bot.marketdata.MarketObservationStore
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.RunInputs
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.replay.domain.SimulationRunNotFoundException
import com.trenching.bot.walletactivity.WalletEventStore
import com.trenching.bot.walletactivity.domain.OrderedWalletTransactions
import com.trenching.bot.walletactivity.domain.WalletTransaction
import org.springframework.stereotype.Service

/**
 * A copy run's inputs are the canonical copies stored under its id; a dataset-referenced run's are its catalog dataset,
 * put in the same canonical order, so both read the same for the same dataset.
 */
@Service
class RunInputsService(
    private val runs: SimulationRuns,
    private val catalog: ReplayDatasetCatalog,
    private val observations: MarketObservationStore,
    private val wallets: WalletEventStore,
) : RunInputs {
    override fun observations(runId: String): List<MarketObservation> {
        val run = requireRun(runId)
        return when (run.storage) {
            RunStorage.COPY -> observations.observations(runId)
            RunStorage.DATASET_REFERENCE -> OrderedMarketData.canonical(catalog.load(run.datasetId).observations)
        }
    }

    override fun transactions(runId: String): List<WalletTransaction> {
        val run = requireRun(runId)
        return when (run.storage) {
            RunStorage.COPY -> wallets.transactions(runId)
            RunStorage.DATASET_REFERENCE -> OrderedWalletTransactions.canonical(catalog.load(run.datasetId).transactions)
        }
    }

    private fun requireRun(runId: String): RunSummary = runs.find(runId) ?: throw SimulationRunNotFoundException(runId)
}
