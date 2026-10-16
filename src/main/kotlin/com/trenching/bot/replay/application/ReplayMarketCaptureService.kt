package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.marketdata.MarketCutoffMode
import com.trenching.bot.marketdata.MarketHistoryCapture
import com.trenching.bot.marketdata.MarketHistoryCaptureRequest
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.marketdata.SolUsdtRateSource
import com.trenching.bot.replay.domain.PlannedWalletHistory
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import com.trenching.bot.replay.domain.ReplayMarketWindowPlan
import com.trenching.bot.walletactivity.RecordedWalletHistory
import java.nio.file.Path

/**
 * Captures market history for a recorded wallet capture's buys. The wallet capture is verified and planned before
 * [marketCapture] is created, so a refused prerequisite never consumes provider configuration. A decided capture asks
 * [cutoffRates], null when no rate source is configured, for the SOL/USDT rates its [DipOutcomeCutoff] replays with.
 */
class ReplayMarketCaptureService(
    walletHistory: RecordedWalletHistory,
    private val marketCapture: () -> MarketHistoryCapture,
    private val cutoffRates: SolUsdtRateSource?,
    planner: ReplayMarketWindowPlanner = ReplayMarketWindowPlanner(),
) {
    private val planning = RecordedWalletPlanning(walletHistory, planner)

    fun capture(request: ReplayMarketCaptureRequest): MarketHistoryCaptureResult {
        val rates = when (request.cutoffMode) {
            MarketCutoffMode.FULL -> null
            MarketCutoffMode.DECIDED -> cutoffRates ?: throw CaptureValidationException(RATES_NOT_CONFIGURED)
        }
        val planned = planning.plan(request.walletCaptureDirectory)
        val plan = planned.plan
        val cutoff = rates?.let { cutoff(planned, it, request) }
        return marketCapture().capture(MarketHistoryCaptureRequest(request.directory, plan.windows, plan.parameters,
            request.scanPages, request.maxTransactions, request.requestBudget, request.byteBudget, request.operationTimeout,
            request.cutoffMode, cutoff, cutoff?.let { DipOutcomeCutoff.IDENTITY }, request.usage))
    }

    fun plan(walletCaptureDirectory: Path): ReplayMarketWindowPlan = planning.plan(walletCaptureDirectory).plan

    private fun cutoff(planned: PlannedWalletHistory, rates: SolUsdtRateSource, request: ReplayMarketCaptureRequest): DipOutcomeCutoff =
        DipOutcomeCutoff(planned.history.wallet, planned.plan.windows.associateWith { planning.anchorTransactions(planned, it) }, rates,
            usage = request.usage)

    private companion object {
        const val RATES_NOT_CONFIGURED = "RATES_NOT_CONFIGURED"
    }
}
