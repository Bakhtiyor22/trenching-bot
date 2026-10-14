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
        val planned = RecordedWalletPlanning(walletHistory).plan(walletDirectory)
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
        val planned = RecordedWalletPlanning(walletHistory).plan(walletDirectory)
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

    private fun markets(progress: MarketCaseProgress, decodedParameters: MarketCaptureWindowParameters) = object : RecordedMarketHistory {
        override fun inspect(directory: Path): MarketHistoryCaptureResult = error("Case inspection reads manifest progress")
        override fun decode(directory: Path): MarketHistoryDecodeResult = error("Case inspection does not decode a whole capture")
        override fun inspectCases(directory: Path): MarketInventory = MarketInventory(directory, listOf(progress))
        override fun decodeCase(directory: Path, caseId: MarketCaseId): MarketCaseDecodeResult {
            decodeCalls++
            return MarketCaseDecodeResult(progress, decodedParameters, MarketObservationExtractorVersion.PUMPFUN_BONDING_CURVE_TRADE_V1,
                emptyList(), MarketObservationExclusion.entries.associateWith { 0 }, 0, true, null)
        }
    }
}
