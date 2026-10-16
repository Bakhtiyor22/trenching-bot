package com.trenching.bot.console.capture

import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.chaincapture.CaptureCheckpointObserver
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.PageResult
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.api.dto.CaptureOperationRequest
import com.trenching.bot.console.mapper.CaptureResponseMapperImpl
import com.trenching.bot.console.mapper.DatasetResponseMapperImpl
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.marketdata.MarketCaseId
import com.trenching.bot.marketdata.MarketObservationExtractorVersion
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.ReferenceRateCaptureResult
import com.trenching.bot.marketdata.ReferenceRateManifest
import com.trenching.bot.replay.CaptureCaseInspection
import com.trenching.bot.replay.CaptureCaseInventory
import com.trenching.bot.replay.CaptureCaseStage
import com.trenching.bot.replay.CaptureInspection
import com.trenching.bot.replay.CapturePaths
import com.trenching.bot.replay.CaptureStepCommand
import com.trenching.bot.replay.CaptureStepResult
import com.trenching.bot.replay.CaptureWorkflow
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.CaptureCaseExport
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.SampleDataset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CaptureCaseServiceTest {
    @TempDir lateinit var root: Path

    @Test
    fun `a ready case captures rates exports and imports while the main operation remains running`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        val main = fixture.operations.start("session-1", CaptureOperationRequest("main-running", CaptureNames.INSPECT,
            fixture.store.session("session-1").marketAttemptId))
        val worker = ManualExecutor()
        val workflow = FakeCaseWorkflow()
        val cases = CaptureCaseService(fixture.store, workflow, CaptureResponseMapperImpl(), DatasetResponseMapperImpl(), FakeCatalog(), fixture.clock, worker)

        val initial = cases.cases("session-1").items.single()
        assertEquals("READY_TO_VALIDATE", initial.readiness)
        assertTrue(initial.canCaptureRates)

        cases.captureRates("session-1", initial.caseId, "case-rates")
        worker.runAll()
        val rated = cases.cases("session-1").items.single()
        assertEquals(listOf(ApiUsageContext(ApiTask.CASE_RATES, "session-1")), workflow.rateUsages)
        assertTrue(rated.validated)
        assertTrue(rated.canExport)
        assertEquals(main.id, fixture.sessions.find("session-1").operation?.id)
        assertEquals(CaptureNames.RUNNING, fixture.sessions.find("session-1").operation?.state)

        cases.export("session-1", initial.caseId, "case-export")
        worker.runAll()
        val exported = cases.cases("session-1").items.single()
        assertTrue(exported.exportReady)
        assertTrue(exported.canRegisterDataset)
        assertTrue(cases.artifact("session-1", initial.caseId).isNotEmpty())

        val registered = cases.register("session-1", initial.caseId)
        assertEquals("dataset-1", registered.dataset.id)
        assertEquals("dataset-1", cases.cases("session-1").items.single().datasetId)
        assertEquals(main.id, fixture.sessions.find("session-1").operation?.id)
    }

    @Test
    fun `a missing case rate blocks export without changing the main operation`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        val before = fixture.sessions.find("session-1").operation
        val worker = ManualExecutor()
        val cases = CaptureCaseService(fixture.store, FakeCaseWorkflow(ratesComplete = false), CaptureResponseMapperImpl(), DatasetResponseMapperImpl(), FakeCatalog(), fixture.clock, worker)
        val caseId = cases.cases("session-1").items.single().caseId

        cases.captureRates("session-1", caseId, "case-rates")
        worker.runAll()

        val blocked = cases.cases("session-1").items.single()
        assertEquals("BLOCKED", blocked.readiness)
        assertEquals("REFERENCE_RATES_INCOMPLETE", blocked.blocker)
        assertFalse(blocked.canExport)
        assertEquals(before?.id, fixture.sessions.find("session-1").operation?.id)
    }

    @Test
    fun `selected case corruption fails validation without changing the main operation`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        val before = fixture.sessions.find("session-1").operation
        val worker = ManualExecutor()
        val workflow = FakeCaseWorkflow(corrupt = true)
        val cases = CaptureCaseService(fixture.store, workflow, CaptureResponseMapperImpl(), DatasetResponseMapperImpl(), FakeCatalog(), fixture.clock, worker)
        val caseId = cases.cases("session-1").items.single().caseId

        cases.captureRates("session-1", caseId, "case-rates")
        worker.runAll()

        val blocked = cases.cases("session-1").items.single()
        assertEquals(CaptureNames.FAILED, blocked.readiness)
        assertEquals("TRANSACTION_HASH_MISMATCH", blocked.blocker)
        assertEquals(before?.id, fixture.sessions.find("session-1").operation?.id)
    }

    @Test
    fun `a case shows how far its market window was observed`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        val cases = CaptureCaseService(fixture.store, FakeCaseWorkflow(observedThrough = 140), CaptureResponseMapperImpl(), DatasetResponseMapperImpl(),
            FakeCatalog(), fixture.clock, ManualExecutor())

        val item = cases.cases("session-1").items.single()

        assertEquals(140L, item.observedThrough)
        assertEquals(140L, CaptureResponseMapperImpl().captureCase(item).observedThrough)
    }

    private class FakeCaseWorkflow(private val ratesComplete: Boolean = true, private val corrupt: Boolean = false, observedThrough: Long? = null) : CaptureWorkflow {
        private val wallet = WalletAddress.of(CaptureConsoleFixture.WALLET)
        private val mint = MintAddress.of(CaptureTokenFixtures.MINT)
        private val signature = TransactionSignature.of("1".repeat(64))
        private val window = MarketCaptureWindow.of(mint, Slot.of(100), Slot.of(200), signature)
        private val parameters = MarketCaptureWindowParameters(100, 10)
        private val summary = CaptureCaseInspection(MarketCaseId.from(window), wallet, mint, signature, 100, 2, window,
            parameters, MarketWindowCoverage.COMPLETE, 4, 4, 0, CaptureCaseStage.READY_TO_VALIDATE, false,
            "MARKET_CASE_NOT_VALIDATED", 0, observedThrough)
        val rateUsages = mutableListOf<ApiUsageContext>()

        override fun inspectCases(walletDirectory: Path, marketDirectory: Path) = CaptureCaseInventory(listOf(summary))

        override fun inspectCase(walletDirectory: Path, marketDirectory: Path, caseId: MarketCaseId): CaptureCaseInspection {
            if (corrupt) throw CaptureValidationException("TRANSACTION_HASH_MISMATCH")
            assertEquals(summary.caseId, caseId)
            return summary.copy(stage = CaptureCaseStage.READY, ready = true, blocker = null, observations = 3)
        }

        override fun execute(command: CaptureStepCommand, checkpoints: CaptureCheckpointObserver): CaptureStepResult = when (command) {
            is CaptureStepCommand.CaseRates -> {
                rateUsages += command.usage
                CaptureStepResult.CaseRates(ReferenceRateCaptureResult(command.directory,
                    ReferenceRateManifest(1, "2026-10-10T09:00:00Z", emptyList(), ratesComplete)))
            }
            is CaptureStepCommand.ExportCase -> {
                Files.createDirectories(command.request.output.parent)
                Files.writeString(command.request.output, "{\"schemaVersion\":3}")
                CaptureStepResult.CaseExported(CaptureCaseExport(command.request.output, "dataset-checksum", command.request.caseId,
                    command.request.wallet, command.request.mint, command.request.anchorSignature, command.request.anchorSlot,
                    command.request.instructionIndex, command.request.fromSlot, command.request.toSlot, command.request.windowParameters,
                    MarketObservationExtractorVersion.PUMPFUN_BONDING_CURVE_TRADE_V1, 1, 3, true))
            }
            else -> throw CaptureValidationException("UNSUPPORTED_IN_FAKE")
        }

        override fun inspect(paths: CapturePaths): CaptureInspection = throw UnsupportedOperationException()
        override fun verdict(inspection: CaptureInspection, datasetExported: Boolean) = throw UnsupportedOperationException()
    }

    private class FakeCatalog : ReplayDatasetCatalog {
        override fun register(name: String, json: java.io.InputStream): DatasetRegistration {
            assertTrue(json.readAllBytes().isNotEmpty())
            return DatasetRegistration(DatasetSummary("dataset-1", name, WalletAddress.of(CaptureConsoleFixture.WALLET), 3,
                1, 3, Slot.of(100), Slot.of(200), emptyList(), FakeCaptureWorkflow.AT), true)
        }
        override fun importSample(sample: SampleDataset): DatasetRegistration = error("unused")
        override fun page(page: Int, size: Int): PageResult<DatasetSummary> = error("unused")
        override fun find(id: String): DatasetSummary? = null
        override fun load(id: String): ReplayDataset = error("unused")
        override fun delete(id: String): Boolean = false
    }
}
