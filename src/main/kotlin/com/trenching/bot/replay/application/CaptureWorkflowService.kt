package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureCheckpointObserver
import com.trenching.bot.marketdata.MarketHistoryCapture
import com.trenching.bot.marketdata.MarketCaseId
import com.trenching.bot.marketdata.RecordedMarketHistory
import com.trenching.bot.marketdata.RecordedReferenceRates
import com.trenching.bot.marketdata.ReferenceRateCapture
import com.trenching.bot.marketdata.ReferenceRateCaptureRequest
import com.trenching.bot.marketdata.SolUsdtRateSource
import com.trenching.bot.replay.CaptureInspection
import com.trenching.bot.replay.CaptureCaseInventory
import com.trenching.bot.replay.CaptureCaseInspection
import com.trenching.bot.replay.CapturePaths
import com.trenching.bot.replay.CaptureStepCommand
import com.trenching.bot.replay.CaptureStepResult
import com.trenching.bot.replay.CaptureWorkflow
import com.trenching.bot.replay.domain.TargetTokenVerdict
import com.trenching.bot.replay.infrastructure.fixture.CalibrationReportWriter
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.walletactivity.RecordedWalletHistory
import com.trenching.bot.walletactivity.WalletHistoryCapture
import tools.jackson.databind.ObjectMapper

class CaptureWorkflowService(
    private val wallets: RecordedWalletHistory,
    private val markets: RecordedMarketHistory,
    private val rates: RecordedReferenceRates,
    private val walletCapture: (CaptureCheckpointObserver) -> WalletHistoryCapture,
    private val marketCapture: (CaptureCheckpointObserver) -> MarketHistoryCapture,
    private val rateCapture: (CaptureCheckpointObserver) -> ReferenceRateCapture,
    private val mapper: ObjectMapper,
    /** The rate source a decided market capture replays with; null when rates are not configured. */
    private val cutoffRates: SolUsdtRateSource?,
) : CaptureWorkflow {
    override fun execute(command: CaptureStepCommand, checkpoints: CaptureCheckpointObserver): CaptureStepResult = when (command) {
        is CaptureStepCommand.Wallet -> CaptureStepResult.Wallet(walletCapture(checkpoints).capture(command.request))
        is CaptureStepCommand.Markets -> CaptureStepResult.Markets(
            ReplayMarketCaptureService(wallets, { marketCapture(checkpoints) }, cutoffRates).capture(command.request))
        is CaptureStepCommand.Rates -> {
            val planned = RecordedWalletPlanning(wallets).plan(command.wallet)
            val market = markets.decode(command.market)
            RecordedMarketVerification.requireMatching(planned.plan, market)
            if (market.observations.isEmpty()) throw com.trenching.bot.chaincapture.CaptureValidationException("NO_OBSERVATIONS")
            CaptureStepResult.Rates(rateCapture(checkpoints).capture(ReferenceRateCaptureRequest(command.market, command.directory)))
        }
        is CaptureStepCommand.CaseRates -> {
            verifiedCase(command.wallet, command.market, command.caseId)
            CaptureStepResult.CaseRates(rateCapture(checkpoints).capture(
                ReferenceRateCaptureRequest(command.market, command.directory, command.caseId)))
        }
        is CaptureStepCommand.Inspect -> CaptureStepResult.Inspected(inspect(command.paths))
        is CaptureStepCommand.Export -> CaptureStepResult.Exported(
            RecordedReplayExportService(wallets, markets, rates, mapper).export(command.request))
        is CaptureStepCommand.ExportCase -> CaptureStepResult.CaseExported(
            RecordedCaseReplayExportService(wallets, markets, rates, mapper).export(command.request))
        is CaptureStepCommand.Calibrate -> {
            val report = ExecutionCalibrationService(wallets, markets).calibrate(command.wallet, command.market)
            val writer = CalibrationReportWriter(mapper)
            writer.save(command.report, report)
            CaptureStepResult.Calibrated(report, writer.write(report), command.report)
        }
        is CaptureStepCommand.Qualify -> CaptureStepResult.Qualified(qualification().qualify(command.dataset, command.paths))
    }

    override fun inspect(paths: CapturePaths): CaptureInspection = CaptureInspectionService(wallets, markets, rates).inspect(paths)

    override fun inspectCases(walletDirectory: java.nio.file.Path, marketDirectory: java.nio.file.Path): CaptureCaseInventory =
        CaptureCaseInspectionService(RecordedWalletPlanning(wallets), markets).inspectCases(walletDirectory, marketDirectory)

    override fun inspectCase(walletDirectory: java.nio.file.Path, marketDirectory: java.nio.file.Path, caseId: MarketCaseId): CaptureCaseInspection =
        CaptureCaseInspectionService(RecordedWalletPlanning(wallets), markets).inspectCase(walletDirectory, marketDirectory, caseId)

    override fun verdict(inspection: CaptureInspection, datasetExported: Boolean): TargetTokenVerdict =
        qualification().fromInspection(inspection, datasetExported)

    private fun verifiedCase(walletDirectory: java.nio.file.Path, marketDirectory: java.nio.file.Path, caseId: MarketCaseId) {
        val planning = RecordedWalletPlanning(wallets)
        val planned = planning.plan(walletDirectory)
        val window = planned.plan.windows.singleOrNull { MarketCaseId.from(it) == caseId }
            ?: throw com.trenching.bot.chaincapture.CaptureValidationException("MARKET_CASE_NOT_FOUND")
        planning.anchor(planned, window)
        val decoded = markets.decodeCase(marketDirectory, caseId)
        if (decoded.windowParameters != planned.plan.parameters) {
            throw com.trenching.bot.chaincapture.CaptureValidationException("MARKET_CAPTURE_MISMATCH")
        }
        if (!decoded.ready) throw com.trenching.bot.chaincapture.CaptureValidationException(decoded.blocker ?: "MARKET_CASE_INCOMPLETE")
    }

    private fun qualification() = TargetTokenQualificationService(ReplayDatasetLoader(mapper), ::inspect)
}
