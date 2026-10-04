package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.MarketHistoryCapture
import com.trenching.bot.marketdata.MarketHistoryCaptureRequest
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import com.trenching.bot.replay.domain.ReplayMarketWindowPlan
import com.trenching.bot.walletactivity.RecordedWalletHistory
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Captures market history for a recorded wallet capture's buys. The wallet capture is verified and planned before
 * [marketCapture] is created, so a refused prerequisite never consumes provider configuration.
 */
class ReplayMarketCaptureService(
    private val walletHistory: RecordedWalletHistory,
    private val marketCapture: () -> MarketHistoryCapture,
    private val planner: ReplayMarketWindowPlanner = ReplayMarketWindowPlanner(),
) {
    private val log = LoggerFactory.getLogger(ReplayMarketCaptureService::class.java)

    fun capture(request: ReplayMarketCaptureRequest): MarketHistoryCaptureResult {
        val plan = plan(request.walletCaptureDirectory)
        return marketCapture().capture(MarketHistoryCaptureRequest(request.directory, plan.windows, plan.parameters,
            request.scanPages, request.maxTransactions, request.requestBudget, request.byteBudget))
    }

    fun plan(walletCaptureDirectory: Path): ReplayMarketWindowPlan {
        val captured = walletHistory.inspect(walletCaptureDirectory)
        if (!captured.manifest.complete) throw CaptureValidationException("WALLET_CAPTURE_INCOMPLETE")
        val plan = planner.plan(walletHistory.decode(walletCaptureDirectory, WalletAddress.of(captured.manifest.wallet)))
        if (plan.windows.isEmpty()) throw CaptureValidationException("NO_SOURCE_BUYS")
        log.info("replay_market_plan sourceBuys={} windows={} firstEntryOffset={} lastEntryOffset={} maxWaitSlots={} holdingSlots={}",
            plan.sourceBuys, plan.windows.size, plan.parameters.firstEntryOffset, plan.parameters.lastEntryOffset,
            plan.parameters.maxWaitSlots, plan.parameters.holdingSlots)
        return plan
    }
}
