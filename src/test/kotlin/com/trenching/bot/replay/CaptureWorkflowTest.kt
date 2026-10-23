package com.trenching.bot.replay

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.*
import com.trenching.bot.replay.application.CaptureWorkflowService
import com.trenching.bot.replay.application.ExecutionCalibrationService
import com.trenching.bot.replay.application.RecordedReplayExportService
import com.trenching.bot.replay.domain.CaptureVerdict
import com.trenching.bot.replay.domain.CaptureVerdictState
import com.trenching.bot.replay.domain.CaseClassification
import com.trenching.bot.replay.domain.RecordedReplayExportRequest
import com.trenching.bot.replay.domain.ReplayVerdict
import com.trenching.bot.replay.infrastructure.fixture.CalibrationReportWriter
import com.trenching.bot.walletactivity.RecordedWalletHistory
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import com.trenching.bot.walletactivity.WalletHistoryDecodeResult
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class CaptureWorkflowTest {
    @TempDir lateinit var root: Path
    private val mapper = JsonMapper.builder().build()
    private val wallets = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper)
    private var providerCalls = 0
    private val wallet get() = root.resolve("wallet")
    private val market get() = root.resolve("market")
    private val rates get() = root.resolve("rates")
    private fun window() = MarketCaptureWindow.of(MintAddress.of(RecordedWalletFixtures.MINT),
        Slot.of(RecordedWalletFixtures.SLOT + 1), Slot.of(RecordedWalletFixtures.SLOT + 4500),
        TransactionSignature.of(RecordedWalletFixtures.SIGNATURE))
    private fun manifest() = ReplayMarketFixtures.manifest(listOf(window()))
    private fun decoded() = ReplayMarketFixtures.decoded(market, manifest(), 1)
    private fun rateResult(complete: Boolean = true) = ReferenceRateCaptureResult(rates,
        ReferenceRateManifest(1, "2026-10-04T00:00:00Z", listOf(ReferenceRateRecord(
            ReferenceRate.minuteOf(decoded().observations.single().observedTime).toString(),
            if (complete) "150.12" else null, if (complete) "fixture" else null, if (complete) null else "UNAVAILABLE")), complete))
    private fun recordedMarkets(decoded: MarketHistoryDecodeResult) = object : RecordedMarketHistory {
        override fun inspect(directory: Path) = MarketHistoryCaptureResult(directory, manifest().copy(complete = decoded.complete))
        override fun decode(directory: Path) = decoded
    }
    private fun recordedRates(result: ReferenceRateCaptureResult) = object : RecordedReferenceRates {
        override fun inspect(directory: Path) = result
        override fun rates(directory: Path): List<ReferenceRate> {
            if (!result.manifest.complete) throw CaptureValidationException("REFERENCE_RATES_INCOMPLETE")
            return result.manifest.records.map { ReferenceRate(Instant.parse(it.minute), BigDecimal(requireNotNull(it.rate)), requireNotNull(it.source)) }
        }
    }
    private fun workflow(decoded: MarketHistoryDecodeResult = decoded(), ratesResult: ReferenceRateCaptureResult = rateResult(), history: WalletHistoryDecodeResult? = null) =
        CaptureWorkflowService(if (history == null) wallets else object : RecordedWalletHistory {
            override fun inspect(directory: Path) = wallets.inspect(directory)
            override fun decode(directory: Path, wallet: WalletAddress) = history
        }, recordedMarkets(decoded), recordedRates(ratesResult),
            { error("Wallet provider must not be constructed") }, { error("Market provider must not be constructed") },
            { object : ReferenceRateCapture {
                override fun capture(request: ReferenceRateCaptureRequest): ReferenceRateCaptureResult {
                    providerCalls++
                    return rateResult()
                }
            } }, mapper, cutoffRates = null)

    @Test
    fun `inspection enables calibration only when coverage reaches the slot before every buy`() {
        RecordedWalletFixtures.save(mapper, wallet)
        val history = wallets.decode(wallet, WalletAddress.of(RecordedWalletFixtures.WALLET))
        val laterSlot = Slot.of(RecordedWalletFixtures.SLOT + 200)
        val laterSignature = TransactionSignature.of("1".repeat(63) + "2")
        val withLaterBuy = history.copy(
            transactions = history.transactions + history.transactions.first().copy(slot = laterSlot, signature = laterSignature),
            buyFacts = history.buyFacts + history.buyFacts.first().copy(slot = laterSlot, signature = laterSignature),
        )
        val partial = decoded().let { market ->
            market.copy(windows = market.windows.map { it.copy(observedThrough = Slot.of(RecordedWalletFixtures.SLOT + 40)) })
        }

        val inspection = workflow(partial, history = withLaterBuy).inspect(CapturePaths(wallet, market, rates))

        assertEquals(CaptureStageState.READY, inspection.market.state)
        assertTrue(inspection.canExport)
        assertFalse(inspection.canCalibrate)
        val sufficient = partial.copy(windows = partial.windows.map { it.copy(observedThrough = Slot.of(laterSlot.value - 1)) })
        assertTrue(workflow(sufficient, history = withLaterBuy).inspect(CapturePaths(wallet, market, rates)).canCalibrate)
    }

    @Test
    fun `acquisition complete market with incomplete coverage refuses rates before constructing provider`() {
        RecordedWalletFixtures.save(mapper, wallet)
        val incomplete = decoded().copy(complete = false,
            windows = decoded().windows.map { it.copy(coverage = MarketWindowCoverage.COVERAGE_INCOMPLETE, yieldsObservations = false) })
        val exception = assertFailsWith<CaptureValidationException> {
            workflow(incomplete).execute(CaptureStepCommand.Rates(wallet, market, rates))
        }
        assertEquals("MARKET_CAPTURE_INCOMPLETE", exception.category)
        assertEquals(0, providerCalls)
    }

    @Test
    fun `wallet only and partial captures remain inspectable without provider calls`() {
        RecordedWalletFixtures.save(mapper, wallet, complete = false)
        val inspection = workflow().inspect(CapturePaths(wallet))
        assertEquals(CaptureStageState.INCOMPLETE, inspection.wallet.state)
        assertTrue(inspection.wallet.canResume)
        assertFalse(inspection.market.canStart)
        assertNull(inspection.marketCapture)
        assertFalse(inspection.canExport)
        assertEquals(0, providerCalls)
    }

    @Test
    fun `complete buy free capture is unusable and cannot unlock markets`() {
        RecordedWalletFixtures.save(mapper, wallet, signer = false)
        val inspection = workflow().inspect(CapturePaths(wallet))
        assertEquals(CaptureStageState.UNUSABLE, inspection.wallet.state)
        assertEquals("NO_SOURCE_BUYS", inspection.wallet.reason)
        assertFalse(inspection.market.canStart)
    }

    @Test
    fun `calibration without rates and export have byte identical service outputs`() {
        RecordedWalletFixtures.save(mapper, wallet)
        val workflow = workflow()
        val inspection = workflow.inspect(CapturePaths(wallet, market))
        assertTrue(inspection.canCalibrate)
        assertFalse(inspection.canExport)
        val result = workflow.execute(CaptureStepCommand.Calibrate(wallet, market, root.resolve("calibration.json"))) as CaptureStepResult.Calibrated
        val cliReport = ExecutionCalibrationService(wallets, recordedMarkets(decoded())).calibrate(wallet, market)
        assertEquals(CalibrationReportWriter(mapper).write(cliReport), result.json)
        assertEquals(result.json, Files.readString(result.output))
        val command = RecordedReplayExportRequest(wallet, market, rates, root.resolve("ui.json"))
        workflow.execute(CaptureStepCommand.Export(command))
        RecordedReplayExportService(wallets, recordedMarkets(decoded()), recordedRates(rateResult()), mapper)
            .export(command.copy(output = root.resolve("cli.json")))
        assertContentEquals(Files.readAllBytes(root.resolve("cli.json")), Files.readAllBytes(root.resolve("ui.json")))
        assertEquals(0, providerCalls)
    }

    @Test
    fun `qualify replays the exported dataset offline`() {
        RecordedWalletFixtures.save(mapper, wallet)
        val workflow = workflow()
        val exported = workflow.execute(CaptureStepCommand.Export(
            RecordedReplayExportRequest(wallet, market, rates, root.resolve("dataset.json")))) as CaptureStepResult.Exported
        val qualified = workflow.execute(CaptureStepCommand.Qualify(exported.export.output, CapturePaths(wallet, market, rates)))
            as CaptureStepResult.Qualified
        assertEquals(ReplayVerdict.Ran, qualified.verdict.replay)
        assertEquals(CaptureVerdict(CaptureVerdictState.COMPLETE), qualified.verdict.capture)
        assertEquals(exported.export.checksum, qualified.verdict.provenance.datasetChecksum)
        assertEquals(0, providerCalls)
    }

    @Test
    fun `qualify of a capture that is not export ready answers from inspection without replaying`() {
        RecordedWalletFixtures.save(mapper, wallet, complete = false)
        val qualified = workflow().execute(CaptureStepCommand.Qualify(root.resolve("dataset.json"), CapturePaths(wallet)))
            as CaptureStepResult.Qualified
        assertEquals(CaseClassification.INCOMPLETE_CAPTURE, qualified.verdict.classification)
        assertEquals(ReplayVerdict.NotRun, qualified.verdict.replay)
        assertEquals(0, providerCalls)
    }

    @Test
    fun `wrong market windows refuse rate source and mark inspection invalid`() {
        RecordedWalletFixtures.save(mapper, wallet)
        val shifted = decoded().copy(windows = decoded().windows.map {
            it.copy(window = MarketCaptureWindow.of(it.window.baseMint, it.window.fromSlot.plus(1), it.window.toSlot, it.window.untilSignature))
        })
        val inspected = workflow(shifted).inspect(CapturePaths(wallet, market))
        assertEquals(CaptureStageState.INVALID, inspected.market.state)
        assertEquals("MARKET_CAPTURE_MISMATCH", inspected.market.reason)
        assertFalse(inspected.rates.canStart)
        assertEquals("MARKET_CAPTURE_MISMATCH", assertFailsWith<CaptureValidationException> {
            workflow(shifted).execute(CaptureStepCommand.Rates(wallet, market, rates))
        }.category)
        assertEquals(0, providerCalls)
    }

    @Test
    fun `a market capture with the lead is matched against the wallet's lead windows and unlocks rates`() {
        RecordedWalletFixtures.save(mapper, wallet)
        val leadWindow = MarketCaptureWindow.of(window().baseMint, Slot.of(RecordedWalletFixtures.SLOT - 50), window().toSlot,
            window().untilSignature)
        val lead = ReplayMarketFixtures.decoded(market,
            ReplayMarketFixtures.manifest(listOf(leadWindow), parameters = ReplayMarketFixtures.LEAD_PARAMETERS), 1)
        val leadWindowsLegacyParameters = lead.copy(windowParameters = ReplayMarketFixtures.PARAMETERS)

        assertEquals(CaptureStageState.READY, workflow(lead).inspect(CapturePaths(wallet, market)).market.state)
        assertTrue((workflow(lead).execute(CaptureStepCommand.Rates(wallet, market, rates)) as CaptureStepResult.Rates).capture.manifest.complete)
        assertEquals("MARKET_CAPTURE_MISMATCH", workflow(leadWindowsLegacyParameters).inspect(CapturePaths(wallet, market)).market.reason)
    }

    @Test
    fun `tampered wallet is invalid and cannot unlock later stages`() {
        RecordedWalletFixtures.save(mapper, wallet)
        val artifact = wallets.inspect(wallet).manifest.records.single().artifact
        Files.writeString(wallet.resolve(requireNotNull(artifact)), "{}")
        val inspected = workflow().inspect(CapturePaths(wallet, market, rates))
        assertEquals(CaptureStageState.INVALID, inspected.wallet.state)
        assertFalse(inspected.market.canResume)
        assertFalse(inspected.rates.canStart)
        assertFalse(inspected.canCalibrate)
        assertFalse(inspected.canExport)
        assertEquals(0, providerCalls)
    }

    @Test
    fun `successful rate acquisition reaches source only after verified matching market`() {
        RecordedWalletFixtures.save(mapper, wallet)
        val result = workflow().execute(CaptureStepCommand.Rates(wallet, market, rates)) as CaptureStepResult.Rates
        assertTrue(result.capture.manifest.complete)
        assertEquals(1, providerCalls)
    }
    @Test
    fun `mismatching rate minutes require fresh attempt and missing rates cannot export`() {
        RecordedWalletFixtures.save(mapper, wallet)
        val wrongRates = rateResult().copy(manifest = rateResult().manifest.copy(records = listOf(
            ReferenceRateRecord("2026-10-01T00:00:00Z", "150.12", "fixture", null))))
        val inspected = workflow(ratesResult = wrongRates).inspect(CapturePaths(wallet, market, rates))
        assertEquals(CaptureStageState.INVALID, inspected.rates.state)
        assertTrue(inspected.rates.requiresNewAttempt)
        assertFalse(inspected.canExport)
        assertFailsWith<CaptureValidationException> {
            workflow(ratesResult = rateResult(false)).execute(CaptureStepCommand.Export(
                RecordedReplayExportRequest(wallet, market, rates, root.resolve("missing.json"))))
        }
        assertFalse(Files.exists(root.resolve("missing.json")))
    }
}
