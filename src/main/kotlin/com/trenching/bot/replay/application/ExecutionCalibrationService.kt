package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.marketdata.RecordedMarketHistory
import com.trenching.bot.replay.domain.CalibrationReport
import com.trenching.bot.replay.domain.VerdictReasons
import com.trenching.bot.walletactivity.RecordedWalletHistory
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Measures the execution model against a wallet capture's real buys, offline. Both captures are verified as for a replay
 * export: a complete wallet capture whose accepted buys plan the market windows, and a market capture of exactly those.
 */
class ExecutionCalibrationService(
    walletHistory: RecordedWalletHistory,
    private val marketHistory: RecordedMarketHistory,
    planner: ReplayMarketWindowPlanner = ReplayMarketWindowPlanner(),
) {
    private val log = LoggerFactory.getLogger(ExecutionCalibrationService::class.java)
    private val planning = RecordedWalletPlanning(walletHistory, planner)

    fun calibrate(walletCaptureDirectory: Path, marketCaptureDirectory: Path): CalibrationReport {
        val history = planning.history(walletCaptureDirectory)
        val market = marketHistory.decode(marketCaptureDirectory)
        val planned = planning.plan(history, market.windowParameters.leadSlots)
        RecordedMarketVerification.requireMatching(planned.plan, market)
        if (!RecordedMarketVerification.coversCalibration(planned.history.buyFacts, market)) {
            log.warn("Execution calibration refused: the market capture ends before the wallet's buys are covered. code={} wallet={}",
                VerdictReasons.MARKET_COVERAGE_ENDED, planned.history.wallet.value)
            throw CaptureValidationException(VerdictReasons.MARKET_COVERAGE_ENDED)
        }
        val buys = ExecutionCalibration.buys(planned.history.buyFacts, market.observations, RecordedMarketVerification.observedThrough(market))
        val report = CalibrationReport(planned.history.wallet, planned.history.decoder, market.extractor, ExecutionCalibration.summary(buys), buys)
        val summary = report.summary
        log.debug("Execution calibration finished. buys={} mathExact={} mathMismatches={} latencyChecked={} noPriorState={} unpredictable={}",
            summary.buys, summary.mathExact, summary.mathMismatches, summary.latencyChecked, summary.noPriorState, summary.unpredictable)
        if (summary.mathMismatches > 0) log.warn("Execution calibration found buys whose bonding-curve math does not match the recorded trade. mismatches={}", summary.mathMismatches)
        return report
    }
}
