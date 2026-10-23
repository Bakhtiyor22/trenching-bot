package com.trenching.bot.replay

import com.trenching.bot.marketdata.MarketCaseDecodeResult
import com.trenching.bot.marketdata.MarketCaseId
import com.trenching.bot.marketdata.MarketCaseInventory as MarketInventory
import com.trenching.bot.marketdata.MarketCaseProgress
import com.trenching.bot.marketdata.MarketCaseStage as MarketStage
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.marketdata.MarketHistoryDecodeResult
import com.trenching.bot.marketdata.MarketObservationExclusion
import com.trenching.bot.marketdata.MarketObservationExtractorVersion
import com.trenching.bot.marketdata.RecordedMarketHistory
import com.trenching.bot.replay.application.RecordedWalletPlanning
import com.trenching.bot.replay.application.CaptureCaseInspectionService
import com.trenching.bot.walletactivity.RecordedWalletHistory
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class CaptureCaseInspectionServiceTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val walletDirectory get() = directory.resolve("wallet")
    private val marketDirectory get() = directory.resolve("market")
    private val walletHistory: RecordedWalletHistory get() = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper)
    private var decodeCalls = 0

    @BeforeEach
    fun saveWallet() = RecordedWalletFixtures.save(mapper, walletDirectory)

    @Test
    fun `case inventory reads manifest progress without decoding artifacts`() {
        val planned = RecordedWalletPlanning(walletHistory).plan(walletDirectory, MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS)
        val progress = MarketCaseProgress(MarketCaseId.from(planned.plan.windows.single()), planned.plan.windows.single(),
            com.trenching.bot.marketdata.MarketWindowCoverage.COMPLETE, MarketStage.READY_TO_VALIDATE, 1, 1, 0, null)
        val market = markets(progress, planned.plan.parameters)

        val cases = CaptureCaseInspectionService(RecordedWalletPlanning(walletHistory), market)
            .inspectCases(walletDirectory, marketDirectory)

        assertEquals(1, cases.cases.size)
        assertEquals(CaptureCaseStage.READY_TO_VALIDATE, cases.cases.single().stage)
        assertEquals(com.trenching.bot.marketdata.MarketWindowCoverage.COMPLETE, cases.cases.single().coverage)
        assertFalse(cases.cases.single().ready)
        assertEquals(0, decodeCalls)
    }

    @Test
    fun `selected case with parameters different from the exact wallet plan is invalid`() {
        val planned = RecordedWalletPlanning(walletHistory).plan(walletDirectory, MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS)
        val window = planned.plan.windows.single()
        val progress = MarketCaseProgress(MarketCaseId.from(window), window,
            com.trenching.bot.marketdata.MarketWindowCoverage.COMPLETE, MarketStage.READY_TO_VALIDATE, 1, 1, 0, null)
        val market = markets(progress, planned.plan.parameters.copy(horizonSlots = planned.plan.parameters.horizonSlots - 1))

        val inspected = CaptureCaseInspectionService(RecordedWalletPlanning(walletHistory), market)
            .inspectCase(walletDirectory, marketDirectory, progress.caseId)

        assertEquals(CaptureCaseStage.INVALID, inspected.stage)
        assertEquals("MARKET_CAPTURE_MISMATCH", inspected.blocker)
        assertFalse(inspected.ready)
        assertTrue(decodeCalls > 0)
    }

    @Test
    fun `a case carries how far its window was observed, also once its artifacts are decoded`() {
        val planned = RecordedWalletPlanning(walletHistory).plan(walletDirectory, MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS)
        val window = planned.plan.windows.single()
        val decidedAt = window.fromSlot.plus(40)
        val progress = MarketCaseProgress(MarketCaseId.from(window), window,
            com.trenching.bot.marketdata.MarketWindowCoverage.COMPLETE, MarketStage.READY_TO_VALIDATE, 1, 1, 0, null, decidedAt)
        val service = CaptureCaseInspectionService(RecordedWalletPlanning(walletHistory), markets(progress, planned.plan.parameters))

        assertEquals(decidedAt.value, service.inspectCases(walletDirectory, marketDirectory).cases.single().observedThrough)
        assertEquals(decidedAt.value, service.inspectCase(walletDirectory, marketDirectory, progress.caseId).observedThrough)
    }

    @Test
    fun `a market directory without a manifest lists the wallet's cases planned with the default lead`() {
        val service = CaptureCaseInspectionService(RecordedWalletPlanning(walletHistory), absentMarkets())

        val listed = service.inspectCases(walletDirectory, marketDirectory).cases.single()
        val inspected = service.inspectCase(walletDirectory, marketDirectory, listed.caseId)

        listOf(listed, inspected).forEach { case ->
            assertEquals(RecordedWalletFixtures.SLOT - MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS, case.window.fromSlot.value)
            assertEquals(MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS, case.windowParameters.leadSlots)
            assertEquals(CaptureCaseStage.DISCOVERY_PENDING, case.stage)
            assertEquals("MARKET_CASE_NOT_CAPTURED", case.blocker)
        }
    }

    @Test
    fun `a capture saved before the lead lists and inspects its case with its legacy window and parameters`() {
        val planned = RecordedWalletPlanning(walletHistory).plan(walletDirectory, MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS)
        val window = planned.plan.windows.single()
        val progress = MarketCaseProgress(MarketCaseId.from(window), window,
            com.trenching.bot.marketdata.MarketWindowCoverage.COMPLETE, MarketStage.READY_TO_VALIDATE, 1, 1, 0, null)
        val service = CaptureCaseInspectionService(RecordedWalletPlanning(walletHistory), markets(progress, planned.plan.parameters))

        val listed = service.inspectCases(walletDirectory, marketDirectory).cases.single()
        val inspected = service.inspectCase(walletDirectory, marketDirectory, listed.caseId)

        assertEquals(RecordedWalletFixtures.SLOT + 1, listed.window.fromSlot.value)
        assertEquals(planned.plan.parameters, listed.windowParameters)
        assertEquals(MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS, listed.windowParameters.leadSlots)
        assertEquals(CaptureCaseStage.READY_TO_VALIDATE, listed.stage)
        assertEquals(CaptureCaseStage.READY, inspected.stage)
        assertEquals(planned.plan.parameters, inspected.windowParameters)
    }

    /** A market directory no capture has written yet: only the manifest-only read may be asked. */
    private fun absentMarkets() = object : RecordedMarketHistory {
        override fun inspect(directory: Path): MarketHistoryCaptureResult = error("Case inspection does not verify artifacts")
        override fun decode(directory: Path): MarketHistoryDecodeResult = error("Case inspection does not decode a whole capture")
        override fun windowParameters(directory: Path): MarketCaptureWindowParameters? = null
        override fun inspectCases(directory: Path): MarketInventory = error("No manifest holds case progress")
        override fun decodeCase(directory: Path, caseId: MarketCaseId): MarketCaseDecodeResult = error("No manifest holds a case")
    }

    private fun markets(progress: MarketCaseProgress, decodedParameters: MarketCaptureWindowParameters) = object : RecordedMarketHistory {
        override fun inspect(directory: Path): MarketHistoryCaptureResult = error("Case inspection reads manifest progress")
        override fun decode(directory: Path): MarketHistoryDecodeResult = error("Case inspection does not decode a whole capture")
        override fun windowParameters(directory: Path): MarketCaptureWindowParameters = decodedParameters
        override fun inspectCases(directory: Path): MarketInventory = MarketInventory(directory, listOf(progress))
        override fun decodeCase(directory: Path, caseId: MarketCaseId): MarketCaseDecodeResult {
            decodeCalls++
            return MarketCaseDecodeResult(progress, decodedParameters, MarketObservationExtractorVersion.PUMPFUN_BONDING_CURVE_TRADE_V1,
                emptyList(), MarketObservationExclusion.entries.associateWith { 0 }, 0, true, null)
        }
    }
}
