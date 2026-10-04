package com.trenching.bot.replay.application

import com.trenching.bot.marketdata.MarketHistoryCapture
import com.trenching.bot.marketdata.MarketHistoryCaptureRequest
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import com.trenching.bot.replay.domain.ReplayMarketWindowPlan
import com.trenching.bot.walletactivity.RecordedWalletHistory
import java.nio.file.Path

/**
 * Captures market history for a recorded wallet capture's buys. The wallet capture is verified and planned before
 * [marketCapture] is created, so a refused prerequisite never consumes provider configuration.
 */
class ReplayMarketCaptureService(
    walletHistory: RecordedWalletHistory,
    private val marketCapture: () -> MarketHistoryCapture,
    planner: ReplayMarketWindowPlanner = ReplayMarketWindowPlanner(),
) {
    private val planning = RecordedWalletPlanning(walletHistory, planner)

    fun capture(request: ReplayMarketCaptureRequest): MarketHistoryCaptureResult {
        val plan = plan(request.walletCaptureDirectory)
        return marketCapture().capture(MarketHistoryCaptureRequest(request.directory, plan.windows, plan.parameters,
            request.scanPages, request.maxTransactions, request.requestBudget, request.byteBudget))
    }

    fun plan(walletCaptureDirectory: Path): ReplayMarketWindowPlan = planning.plan(walletCaptureDirectory).plan
}
