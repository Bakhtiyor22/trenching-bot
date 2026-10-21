package com.trenching.bot.replay

import com.trenching.bot.common.MintAddress
import com.trenching.bot.marketdata.MarketCaptureReasons
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.marketdata.MarketHistoryManifest
import com.trenching.bot.marketdata.MarketListedSignature
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.MarketWindowRecord
import com.trenching.bot.marketdata.MarketHistoryDecodeResult
import com.trenching.bot.marketdata.RecordedMarketHistory
import com.trenching.bot.marketdata.RecordedReferenceRates
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.ReferenceRateCaptureResult
import com.trenching.bot.replay.RecordedWalletPlanningTest.Companion.MINT
import com.trenching.bot.replay.RecordedWalletPlanningTest.Companion.SIGNATURE
import com.trenching.bot.replay.RecordedWalletPlanningTest.Companion.SLOT
import com.trenching.bot.replay.RecordedWalletPlanningTest.Companion.withTarget
import com.trenching.bot.replay.application.CaptureInspectionService
import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.RecordedWalletHistory
import com.trenching.bot.walletactivity.TokenAccountFirstBuy
import com.trenching.bot.walletactivity.TokenAccountGap
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class CaptureInspectionServiceTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val recorded = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper)

    @Test
    fun `an existing scope inspects as before and carries no target token`() {
        RecordedWalletFixtures.save(mapper, directory)
        val inspection = inspect(recorded)
        assertEquals(CaptureStageState.READY, inspection.wallet.state)
        assertTrue(inspection.market.canStart)
        assertNull(inspection.targetToken)
    }

    @Test
    fun `a proven first buy makes the wallet stage ready and copies the evidence`() {
        RecordedWalletFixtures.save(mapper, directory)
        val evidence = TargetTokenFixtures.provenEvidence(MINT, SIGNATURE, SLOT)
        val inspection = inspect(withTarget(recorded, evidence))
        assertEquals(CaptureStageState.READY, inspection.wallet.state)
        assertTrue(inspection.market.canStart)
        assertEquals(evidence, inspection.targetToken)
    }

    @Test
    fun `every refused first-buy state leaves a complete wallet capture unusable with its reason`() {
        RecordedWalletFixtures.save(mapper, directory)
        TargetTokenFixtures.refusals(SIGNATURE, SLOT).forEach { (parts, reason) ->
            val evidence = TargetTokenFixtures.evidence(MINT, parts)
            val inspection = inspect(withTarget(recorded, evidence))
            assertEquals(CaptureStageSummary(CaptureStageState.UNUSABLE, reason), inspection.wallet, parts.toString())
            assertFalse(inspection.market.canStart)
            assertEquals(evidence, inspection.targetToken)
        }
    }

    @Test
    fun `an incomplete wallet + token capture stays incomplete`() {
        RecordedWalletFixtures.save(mapper, directory, complete = false)
        val inspection = inspect(withTarget(recorded, TargetTokenFixtures.provenEvidence(MINT, SIGNATURE, SLOT)))
        assertEquals(CaptureStageState.INCOMPLETE, inspection.wallet.state)
        assertTrue(inspection.wallet.canResume)
        assertNull(inspection.targetToken)
    }

    @Test
    fun `a wallet + token capture capped before the end of its history is incomplete for good but still reports its first-buy checks`() {
        listOf(false, true).forEach { savedComplete ->
            val target = directory.resolve("saved-complete-$savedComplete")
            RecordedWalletFixtures.saveCappedTokenCapture(mapper, target, savedComplete)

            val inspection = CaptureInspectionService(recorded, unusedMarkets, unusedRates).inspect(CapturePaths(target))

            assertEquals(CaptureStageSummary(CaptureStageState.INCOMPLETE, "HISTORY_NOT_EXHAUSTED", requiresNewAttempt = true), inspection.wallet)
            assertFalse(inspection.market.canStart)
            assertEquals(TokenAccountFirstBuy.NotEstablished(TokenAccountGap.HISTORY_NOT_EXHAUSTED, emptyList()), inspection.targetToken?.tokenAccountFirstBuy)
            assertEquals(ActualFirstBuy.NotVerified, inspection.targetToken?.actualFirstBuy)
        }
    }

    @Test
    fun `a proven first buy the decoded buys do not start from is invalid`() {
        RecordedWalletFixtures.save(mapper, directory)
        val inspection = inspect(withTarget(recorded, TargetTokenFixtures.provenEvidence(MINT, SIGNATURE, SLOT + 1)))
        assertEquals(CaptureStageSummary(CaptureStageState.INVALID, "TARGET_WINDOW_MISMATCH"), inspection.wallet)
        assertFalse(inspection.market.canStart)
    }

    @Test
    fun `a market window that may have been listed before its end needs a new market attempt`() {
        RecordedWalletFixtures.save(mapper, directory)
        val listed = MarketWindowRecord(MINT, MintAddress.WRAPPED_SOL.value, SLOT + 1, SLOT + 4_500, SIGNATURE, MarketWindowCoverage.COMPLETE,
            pagesScanned = 1, scanned = 1, skippedFailed = 0, skippedNewer = 0, selected = 1,
            selection = listOf(MarketListedSignature(SIGNATURE, SLOT + 2)), scanCursor = MarketListedSignature(SIGNATURE, SLOT + 2))
        val manifest = MarketHistoryManifest(2, "genesis", "confirmed", "2026-10-19T16:02:36Z", MarketCaptureWindowParameters(4_500, 2), 50,
            50_000, listOf(listed), emptyList(), acquisitionComplete = false, complete = false, requestsUsed = 1, bytesUsed = 1)
        val markets = object : RecordedMarketHistory {
            override fun inspect(directory: Path): MarketHistoryCaptureResult = MarketHistoryCaptureResult(directory, manifest)
            override fun decode(directory: Path): MarketHistoryDecodeResult = error("A flagged capture is not decoded")
        }

        val market = CaptureInspectionService(recorded, markets, unusedRates).inspect(CapturePaths(directory, directory)).market

        assertEquals(CaptureStageState.UNUSABLE, market.state)
        assertEquals(MarketCaptureReasons.MARKET_LISTED_BEFORE_END, market.reason)
        assertTrue(market.requiresNewAttempt)
        assertFalse(market.canResume)
    }

    private fun inspect(wallets: RecordedWalletHistory): CaptureInspection =
        CaptureInspectionService(wallets, unusedMarkets, unusedRates).inspect(CapturePaths(directory))

    private val unusedMarkets = object : RecordedMarketHistory {
        override fun inspect(directory: Path): MarketHistoryCaptureResult = error("No market capture is inspected")
        override fun decode(directory: Path): MarketHistoryDecodeResult = error("No market capture is decoded")
    }

    private val unusedRates = object : RecordedReferenceRates {
        override fun inspect(directory: Path): ReferenceRateCaptureResult = error("No rate capture is inspected")
        override fun rates(directory: Path): List<ReferenceRate> = error("No rate capture is read")
    }
}
