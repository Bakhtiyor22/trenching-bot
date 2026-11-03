package com.trenching.bot.console.automation

import com.trenching.bot.apiusage.ApiUsagePeriod
import com.trenching.bot.apiusage.ApiUsageReport
import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.apiusage.ApiUsageTotals
import com.trenching.bot.chaincapture.CaptureCheckpointObserver
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.PageResult
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.capture.CaptureCatalogService
import com.trenching.bot.console.capture.CaptureFileStore
import com.trenching.bot.console.capture.CaptureIds
import com.trenching.bot.console.capture.CaptureOperationService
import com.trenching.bot.console.capture.CapturePathPolicy
import com.trenching.bot.console.capture.CaptureSessionService
import com.trenching.bot.console.capture.CaptureTokenFixtures
import com.trenching.bot.console.capture.ManualCaptureRetryScheduler
import com.trenching.bot.console.capture.ManualExecutor
import com.trenching.bot.console.mapper.CaptureResponseMapperImpl
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.marketdata.ReferenceRateCaptureResult
import com.trenching.bot.marketdata.ReferenceRateManifest
import com.trenching.bot.replay.CaptureInspection
import com.trenching.bot.replay.CapturePaths
import com.trenching.bot.replay.CaptureStageState
import com.trenching.bot.replay.CaptureStageSummary
import com.trenching.bot.replay.CaptureStepCommand
import com.trenching.bot.replay.CaptureStepResult
import com.trenching.bot.replay.CaptureWorkflow
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.ReplayMarketFixtures
import com.trenching.bot.replay.domain.CaseClassification
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.domain.RecordedReplayExport
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.SampleDataset
import com.trenching.bot.replay.domain.TargetTokenVerdict
import com.trenching.bot.walletactivity.WalletHistoryCaptureResult
import com.trenching.bot.walletactivity.WalletHistoryScope
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.reflect.KClass
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * A capture workflow where every step succeeds offline, unless the test queues a failure for that kind of command. Every stage
 * can start and the capture can export; [verdict] answers with [verdictClassification] and a qualification with
 * [qualifyClassification].
 */
class ScriptedCaptureWorkflow : CaptureWorkflow {
    val executed = mutableListOf<CaptureStepCommand>()
    private val failures = mutableMapOf<KClass<out CaptureStepCommand>, ArrayDeque<String>>()
    var verdictClassification = CaseClassification.INCOMPLETE_CAPTURE
    var qualifyClassification = CaseClassification.QUALIFYING_DIP

    /** The next [times] commands of [kind] fail with the capture category [code]. */
    fun fail(kind: KClass<out CaptureStepCommand>, code: String, times: Int = 1) {
        repeat(times) { failures.getOrPut(kind) { ArrayDeque() }.addLast(code) }
    }

    override fun execute(command: CaptureStepCommand, checkpoints: CaptureCheckpointObserver): CaptureStepResult {
        executed += command
        failures[command::class]?.removeFirstOrNull()?.let { throw CaptureValidationException(it) }
        return when (command) {
            is CaptureStepCommand.Inspect -> CaptureStepResult.Inspected(inspect(command.paths))
            is CaptureStepCommand.Wallet -> CaptureStepResult.Wallet(WalletHistoryCaptureResult(command.request.directory,
                CaptureTokenFixtures.manifest(WalletHistoryScope.WALLET_TOKEN_ACCOUNT, CaptureTokenFixtures.MINT)))
            is CaptureStepCommand.Markets -> CaptureStepResult.Markets(MarketHistoryCaptureResult(command.request.directory,
                ReplayMarketFixtures.manifest(listOf(MarketCaptureWindow.of(MintAddress.of(CaptureTokenFixtures.MINT), Slot.of(101), Slot.of(4600),
                    TransactionSignature.of("1".repeat(64)))))))
            is CaptureStepCommand.Rates -> CaptureStepResult.Rates(ReferenceRateCaptureResult(command.directory,
                ReferenceRateManifest(1, "2026-11-02T15:40:00Z", emptyList(), true)))
            is CaptureStepCommand.Export -> {
                Files.createDirectories(command.request.output.parent)
                Files.writeString(command.request.output, EXPORTED)
                CaptureStepResult.Exported(RecordedReplayExport(command.request.output, "checksum", WalletAddress.of(AutomationFixtures.WALLET), 1, 1, 1, 1, true))
            }
            is CaptureStepCommand.Qualify -> CaptureStepResult.Qualified(CaptureTokenFixtures.takeProfit.copy(classification = qualifyClassification))
            else -> throw CaptureValidationException("UNSUPPORTED_IN_FAKE")
        }
    }

    override fun inspect(paths: CapturePaths): CaptureInspection = CaptureInspection(STARTABLE, STARTABLE, STARTABLE, canCalibrate = false, canExport = true)

    override fun verdict(inspection: CaptureInspection, datasetExported: Boolean): TargetTokenVerdict =
        CaptureTokenFixtures.inspectionOnly(verdictClassification, datasetExported)

    private companion object {
        const val EXPORTED = """{"schemaVersion":6}"""
        val STARTABLE = CaptureStageSummary(CaptureStageState.NOT_STARTED, canStart = true)
    }
}

/** A catalog keyed by the dataset's content, like the real checksum-keyed one. */
class FakeDatasetCatalog : ReplayDatasetCatalog {
    val stored = mutableMapOf<String, ByteArray>()

    override fun register(name: String, json: InputStream): DatasetRegistration {
        val bytes = json.readAllBytes()
        val id = "dataset-${bytes.contentHashCode().toUInt()}"
        val created = stored.putIfAbsent(id, bytes) == null
        return DatasetRegistration(DatasetSummary(id, name, WalletAddress.of(AutomationFixtures.WALLET), 6, 1, 1, null, null, emptyList(), AutomationFixtures.EVENING), created)
    }

    override fun importSample(sample: SampleDataset): DatasetRegistration = error("unused")
    override fun page(page: Int, size: Int): PageResult<DatasetSummary> = error("unused")
    override fun find(id: String): DatasetSummary? = error("unused")
    override fun load(id: String): ReplayDataset = error("unused")
    override fun delete(id: String): Boolean = error("unused")
}

/**
 * The real capture services over a temporary root with [ScriptedCaptureWorkflow] behind them. Operations queue on a
 * [ManualExecutor] until [work] runs them, so a running operation can be observed, and automatic retries wait on [retries] until
 * the test runs them. [close] releases this process's file lock; a new harness over the same root then recovers the saved
 * operations, as the backend does on a restart. With [rpcConfigured] off, wallet and market operations are refused
 * `NOT_CONFIGURED`, as without `SOLANA_RPC_URL`.
 */
class CaptureHarness(
    val root: Path,
    clock: Clock,
    val workflow: ScriptedCaptureWorkflow = ScriptedCaptureWorkflow(),
    rpcConfigured: Boolean = true,
) {
    private val json: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private var nextId = 0
    val executor = ManualExecutor()
    val retries = ManualCaptureRetryScheduler()
    val store = CaptureFileStore(CapturePathPolicy(root), json).also { it.initialize() }
    val operations = CaptureOperationService(store, workflow, CaptureResponseMapperImpl(), json, clock, executor, rpcConfigured, true, retries)
        .also { it.recover() }
    val sessions = CaptureSessionService(store, operations, clock, CaptureIds { "id-${++nextId}" }, true)
    val catalog = FakeDatasetCatalog()
    val datasets = CaptureCatalogService(store, operations, catalog)

    fun work() = executor.runAll()

    fun close() = store.close()
}

/** Usage the test sets: [lastDay] for the rolling 24 hours of every capture, and [byCapture] for each capture session's whole life. */
class FakeApiUsageReports : ApiUsageReports {
    var lastDay = 0L
    val byCapture = mutableMapOf<String, Long>()

    override fun report(period: ApiUsagePeriod, captureId: String?): ApiUsageReport {
        val requests = when (period) {
            ApiUsagePeriod.LAST_24_HOURS -> lastDay.also { check(captureId == null) { "the cap counts every capture" } }
            ApiUsagePeriod.ALL -> byCapture[requireNotNull(captureId) { "an item counts its own capture" }] ?: 0L
            ApiUsagePeriod.LAST_HOUR, ApiUsagePeriod.LAST_7_DAYS -> error("unexpected period $period")
        }
        return ApiUsageReport(AutomationFixtures.EVENING.minus(Duration.ofDays(1)), AutomationFixtures.EVENING, 300, null,
            ApiUsageTotals(requests, 0, 0, 0, 0), emptyList(), emptyList(), emptyList(), emptyList())
    }
}
