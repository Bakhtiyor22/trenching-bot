package com.trenching.bot.console

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.PageResult
import com.trenching.bot.console.application.ConsoleService
import com.trenching.bot.console.application.DatasetSourceService
import com.trenching.bot.console.application.RunUsdtViewService
import com.trenching.bot.console.capture.CaptureCaseRecord
import com.trenching.bot.console.capture.CaptureFileSourceLookup
import com.trenching.bot.console.capture.CaptureFileStore
import com.trenching.bot.console.capture.CapturePathPolicy
import com.trenching.bot.console.capture.CaptureSessionRecord
import com.trenching.bot.console.domain.CaptureSource
import com.trenching.bot.console.domain.DatasetSource
import com.trenching.bot.console.domain.StoredReportFacts
import com.trenching.bot.console.report.RunReportReader
import com.trenching.bot.marketdata.MarketObservationStore
import com.trenching.bot.marketdata.RateLookup
import com.trenching.bot.marketdata.RateUnavailableReason
import com.trenching.bot.marketdata.SolUsdtRateSource
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.portfolio.PositionStore
import com.trenching.bot.portfolio.domain.StoredPosition
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.replay.domain.SampleDataset
import com.trenching.bot.strategy.TradeIntentStore
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.walletactivity.WalletEventStore
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import java.io.InputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class ConsoleServiceRunDetailTest {
    @TempDir lateinit var root: Path

    private val sample = DipSample.load()
    private val report = ReplayRunner().run(sample, SimulationConfig.mvp())
    private val runs: SimulationRuns = Mockito.mock(SimulationRuns::class.java)
    private val reports: RunReportReader = Mockito.mock(RunReportReader::class.java)
    private val events: WalletEventStore = Mockito.mock(WalletEventStore::class.java)
    private val observations: MarketObservationStore = Mockito.mock(MarketObservationStore::class.java)
    private val intents: TradeIntentStore = Mockito.mock(TradeIntentStore::class.java)
    private val orders: PaperOrderStore = Mockito.mock(PaperOrderStore::class.java)
    private val positions: PositionStore = Mockito.mock(PositionStore::class.java)
    private val run = RunSummary(report.runId, DATASET_ID, report.engineVersion, report.configurationHash, report.resultChecksum,
        MintAddress.WRAPPED_SOL, 10, 5, 5, 3, 2, BigDecimal(DipSample.TOTAL_PNL), Instant.parse("2026-10-08T00:00:00Z"))
    private lateinit var store: CaptureFileStore
    private lateinit var service: ConsoleService

    @BeforeEach
    fun stub() {
        store = CaptureFileStore(CapturePathPolicy(root), JsonMapper.builder().addModule(kotlinModule()).build()).also { it.initialize() }
        service = ConsoleService(runs, reports, events, observations, intents, orders, positions,
            DatasetSourceService(DatasetCatalog(), CaptureFileSourceLookup(store)))
        val trades = report.ingestion.filterIsInstance<NormalizationOutcome.Accepted>().map { it.trade }
            .map { it.copy(blockTime = if (it.pair == DipSample.pair(DipSample.STOP_LOSS)) null else MIDNIGHT.plusSeconds(it.slot.value)) }
        Mockito.`when`(runs.find(run.id)).thenReturn(run)
        Mockito.`when`(runs.report(run.id)).thenReturn("{}")
        Mockito.`when`(reports.read("{}")).thenReturn(StoredReportFacts(SimulationConfig.mvp()))
        Mockito.`when`(observations.observations(run.id)).thenReturn(sample.observations)
        Mockito.`when`(intents.intents(run.id)).thenReturn(report.scenarios.map { it.intent })
        Mockito.`when`(orders.outcomes(run.id)).thenReturn(report.scenarios.map { it.outcome })
        Mockito.`when`(positions.storedPositions(run.id))
            .thenReturn(report.scenarios.mapNotNull { result -> result.position?.let { StoredPosition(it, result.exitUnavailable) } })
        Mockito.`when`(events.acceptedTrades(run.id)).thenReturn(trades)
    }

    @AfterEach
    fun close() = store.close()

    @Test
    fun `a run on a dataset registered from a capture case names its dataset, capture and case`() {
        store.save(CaptureSessionRecord(id = "s1", requestId = "s1", fingerprint = "s1", name = CAPTURE_NAME, createdAt = Instant.parse("2026-10-16T20:13:00Z"),
            wallet = null, walletAttemptId = "w1", marketAttemptId = "m1", ratesAttemptId = null), create = true)
        store.save("s1", CaptureCaseRecord(caseId = "case-1", walletAttemptId = "w1", marketAttemptId = "m1", ratesAttemptId = null, datasetId = DATASET_ID))

        val source = service.detailWithSource(run.id).source

        assertEquals(DatasetSource(DATASET_ID, DATASET_NAME, CaptureSource("s1", CAPTURE_NAME, "case-1")), source)
    }

    @Test
    fun `a run whose capture is gone still names its dataset`() {
        assertEquals(DatasetSource(DATASET_ID, DATASET_NAME, null), service.detailWithSource(run.id).source)
    }

    @Test
    fun `each scenario's first buy is the block time of the tracked wallet's buy it started from`() {
        val scenarios = service.detailWithSource(run.id).scenarios

        val firstBuys = scenarios.associate { it.scenario.intent.pair to it.timeline.firstBuyAt }
        assertEquals(Instant.parse("2026-10-01T00:16:40Z"), firstBuys[DipSample.pair(DipSample.TAKE_PROFIT)])
        assertEquals(Instant.parse("2026-10-01T00:50:00Z"), firstBuys[DipSample.pair(DipSample.NO_DIP)])
        assertNull(firstBuys.getValue(DipSample.pair(DipSample.STOP_LOSS)))
    }

    @Test
    fun `the chart and USDT reads of a run never load its accepted trades`() {
        service.detailWithObservations(run.id)
        RunUsdtViewService(service, NoRates).view(run.id)

        Mockito.verify(events, Mockito.never()).acceptedTrades(run.id)
    }

    @Test
    fun `an unreadable capture session leaves the run's capture unnamed`() {
        Files.createDirectories(root.resolve("console/half-written"))

        assertEquals(DatasetSource(DATASET_ID, DATASET_NAME, null), service.detailWithSource(run.id).source)
    }

    private object NoRates : SolUsdtRateSource {
        override fun minuteRates(minutes: Set<Instant>): Map<Instant, RateLookup> =
            minutes.associateWith { RateLookup.Unavailable(RateUnavailableReason.NOT_CONFIGURED) }
        override fun current(): RateLookup = error("unused")
    }

    private inner class DatasetCatalog : ReplayDatasetCatalog {
        override fun find(id: String): DatasetSummary? = if (id == DATASET_ID) {
            DatasetSummary(id, DATASET_NAME, sample.trackedWallet, sample.schemaVersion, sample.transactions.size, sample.observations.size, null, null,
                emptyList(), Instant.EPOCH)
        } else {
            null
        }
        override fun register(name: String, json: InputStream): DatasetRegistration = error("unused")
        override fun importSample(sample: SampleDataset): DatasetRegistration = error("unused")
        override fun page(page: Int, size: Int): PageResult<DatasetSummary> = error("unused")
        override fun load(id: String): ReplayDataset = error("unused")
        override fun delete(id: String): Boolean = error("unused")
    }

    private companion object {
        const val DATASET_ID = "dataset-from-case"
        const val DATASET_NAME = "Decu case dataset"
        const val CAPTURE_NAME = "Decu · 7mup…pump · 16 Oct 20:13"
        val MIDNIGHT: Instant = Instant.parse("2026-10-01T00:00:00Z")
    }
}
