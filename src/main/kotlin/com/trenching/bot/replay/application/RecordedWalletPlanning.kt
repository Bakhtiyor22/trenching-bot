package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.replay.domain.PlannedWalletHistory
import com.trenching.bot.walletactivity.RecordedWalletHistory
import java.nio.file.Path
import org.slf4j.LoggerFactory

/** Verifies a recorded wallet capture is complete, decodes it and plans the market windows of the buys the strategy accepts. */
class RecordedWalletPlanning(
    private val walletHistory: RecordedWalletHistory,
    private val planner: ReplayMarketWindowPlanner = ReplayMarketWindowPlanner(),
) {
    private val log = LoggerFactory.getLogger(RecordedWalletPlanning::class.java)

    fun plan(walletCaptureDirectory: Path): PlannedWalletHistory {
        val captured = walletHistory.inspect(walletCaptureDirectory)
        if (!captured.manifest.complete) throw CaptureValidationException("WALLET_CAPTURE_INCOMPLETE")
        val history = walletHistory.decode(walletCaptureDirectory, WalletAddress.of(captured.manifest.wallet))
        val plan = planner.plan(history)
        if (plan.windows.isEmpty()) throw CaptureValidationException("NO_SOURCE_BUYS")
        log.info("replay_market_plan sourceBuys={} windows={} firstEntryOffset={} lastEntryOffset={} maxWaitSlots={} holdingSlots={}",
            plan.sourceBuys, plan.windows.size, plan.parameters.firstEntryOffset, plan.parameters.lastEntryOffset,
            plan.parameters.maxWaitSlots, plan.parameters.holdingSlots)
        return PlannedWalletHistory(history, plan)
    }
}
