package com.trenching.bot.replay

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.PageResult
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.MarketObservationStore
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.replay.application.RunInputsService
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.RunAggregates
import com.trenching.bot.replay.domain.RunStart
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.replay.domain.SampleDataset
import com.trenching.bot.replay.domain.SimulationRunNotFoundException
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.walletactivity.WalletEventStore
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import com.trenching.bot.walletactivity.domain.OrderedWalletTransactions
import com.trenching.bot.walletactivity.domain.WalletTradeObserved
import com.trenching.bot.walletactivity.domain.WalletTransaction
import java.io.InputStream
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RunInputsServiceTest {
    private val sample = DipSample.load()
    private val shuffled = sample.copy(observations = sample.observations.reversed(), transactions = sample.transactions.reversed())
    private val runs = Runs()
    private val stores = Stores()
    private val service = RunInputsService(runs, Catalog(), stores, stores)

    @Test
    fun `a copy run reads the observations and transactions stored under its id`() {
        runs.add(COPY_RUN, RunStorage.COPY)
        stores.storeCopy(COPY_RUN, shuffled)

        assertEquals(OrderedMarketData.canonical(sample.observations), service.observations(COPY_RUN))
        assertEquals(OrderedWalletTransactions.canonical(sample.transactions), service.transactions(COPY_RUN))
        assertEquals(listOf(COPY_RUN, COPY_RUN), stores.reads)
    }

    @Test
    fun `a dataset-referenced run reads its dataset's inputs exactly as a copy run of that dataset stores them`() {
        runs.add(COPY_RUN, RunStorage.COPY)
        runs.add(REFERENCE_RUN, RunStorage.DATASET_REFERENCE)
        stores.storeCopy(COPY_RUN, shuffled)
        assertNotEquals(shuffled.observations, OrderedMarketData.canonical(shuffled.observations))

        assertEquals(service.observations(COPY_RUN), service.observations(REFERENCE_RUN))
        assertEquals(service.transactions(COPY_RUN), service.transactions(REFERENCE_RUN))
        assertTrue(REFERENCE_RUN !in stores.reads)
    }

    @Test
    fun `an unknown run is not found and nothing is read`() {
        assertFailsWith<SimulationRunNotFoundException> { service.observations("missing") }
        assertFailsWith<SimulationRunNotFoundException> { service.transactions("missing") }
        assertEquals(emptyList(), stores.reads)
    }

    private inner class Runs : SimulationRuns {
        private val summaries = mutableMapOf<String, RunSummary>()

        fun add(id: String, storage: RunStorage) {
            summaries[id] = RunSummary(id, DATASET_ID, "engine", "config", "result", MintAddress.WRAPPED_SOL, 0, 0, 0, 0, 0,
                BigDecimal.ZERO, Instant.EPOCH, storage)
        }

        override fun find(runId: String): RunSummary? = summaries[runId]
        override fun start(datasetId: String, config: SimulationConfig, storage: RunStorage): RunStart = error("unused")
        override fun page(page: Int, size: Int, datasetId: String?): PageResult<RunSummary> = error("unused")
        override fun report(runId: String): String = error("unused")
        override fun totals(createdSince: Instant): RunAggregates = error("unused")
    }

    private inner class Catalog : ReplayDatasetCatalog {
        override fun load(id: String): ReplayDataset = if (id == DATASET_ID) shuffled else error("unexpected dataset $id")
        override fun register(name: String, json: InputStream): DatasetRegistration = error("unused")
        override fun importSample(sample: SampleDataset): DatasetRegistration = error("unused")
        override fun page(page: Int, size: Int): PageResult<DatasetSummary> = error("unused")
        override fun find(id: String): DatasetSummary? = error("unused")
        override fun delete(id: String): Boolean = error("unused")
    }

    /** Stores a copy run's inputs as `SimulationRunTransaction` records them: canonical order, under the run id. */
    private class Stores : MarketObservationStore, WalletEventStore {
        private val observations = mutableMapOf<String, List<MarketObservation>>()
        private val transactions = mutableMapOf<String, List<WalletTransaction>>()
        val reads = mutableListOf<String>()

        fun storeCopy(runId: String, dataset: ReplayDataset) {
            observations[runId] = OrderedMarketData.canonical(dataset.observations)
            transactions[runId] = OrderedWalletTransactions.canonical(dataset.transactions)
        }

        override fun observations(runId: String): List<MarketObservation> {
            reads += runId
            return observations[runId].orEmpty()
        }

        override fun transactions(runId: String): List<WalletTransaction> {
            reads += runId
            return transactions[runId].orEmpty()
        }

        override fun record(runId: String, observations: List<MarketObservation>) = error("unused")
        override fun record(runId: String, trackedWallet: WalletAddress, transactions: List<WalletTransaction>,
                            outcomes: List<NormalizationOutcome>) = error("unused")
        override fun acceptedTrades(runId: String): List<WalletTradeObserved> = error("unused")
        override fun ignored(runId: String): List<NormalizationOutcome.Ignored> = error("unused")
    }

    private companion object {
        const val DATASET_ID = "dataset-1"
        const val COPY_RUN = "run-copy"
        const val REFERENCE_RUN = "run-reference"
    }
}
