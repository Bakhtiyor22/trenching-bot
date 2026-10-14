package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.common.MintAddress
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.replay.domain.MarketPlanDecision
import com.trenching.bot.replay.domain.PlannedWalletHistory
import com.trenching.bot.walletactivity.RecordedWalletHistory
import com.trenching.bot.walletactivity.application.WalletNormalizer
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import com.trenching.bot.walletactivity.domain.TradeSide
import com.trenching.bot.walletactivity.domain.WalletTradeObserved
import java.nio.file.Path
import org.slf4j.LoggerFactory

/** Verifies a recorded wallet capture is complete, decodes it and plans the market windows of the buys the strategy accepts. */
class RecordedWalletPlanning(
    private val walletHistory: RecordedWalletHistory,
    planner: ReplayMarketWindowPlanner = ReplayMarketWindowPlanner(),
) {
    private val log = LoggerFactory.getLogger(RecordedWalletPlanning::class.java)
    private val gate = MarketPlanGate(planner)
    private val normalizer = WalletNormalizer()

    fun plan(walletCaptureDirectory: Path): PlannedWalletHistory {
        val captured = walletHistory.inspect(walletCaptureDirectory)
        if (!captured.manifest.complete) throw CaptureValidationException("WALLET_CAPTURE_INCOMPLETE")
        val history = walletHistory.decode(walletCaptureDirectory, WalletAddress.of(captured.manifest.wallet))
        val plan = when (val decision = gate.plan(history)) {
            is MarketPlanDecision.Planned -> decision.plan
            is MarketPlanDecision.Refused -> {
                log.warn("replay_market_plan refused reason={} wallet={}", decision.reason, history.wallet.value)
                throw CaptureValidationException(decision.reason)
            }
        }
        log.info("replay_market_plan sourceBuys={} windows={} horizonSlots={} maxAgeSlots={}",
            plan.sourceBuys, plan.windows.size, plan.parameters.horizonSlots, plan.parameters.maxAgeSlots)
        return PlannedWalletHistory(history, plan)
    }

    /** Resolves a planned window to the exact accepted first-buy instruction that anchors it. */
    fun anchor(planned: PlannedWalletHistory, window: MarketCaptureWindow): WalletTradeObserved {
        if (window !in planned.plan.windows) throw CaptureValidationException("MARKET_CAPTURE_MISMATCH")
        val anchor = planned.history.transactions.map { normalizer.normalize(it, planned.history.wallet, MintAddress.WRAPPED_SOL) }
            .filterIsInstance<NormalizationOutcome.Accepted>()
            .map { it.trade }
            .filter { it.side == TradeSide.BUY && it.pair.base == window.baseMint }
            .minWithOrNull(compareBy({ it.slot.value }, { it.signature.value }, { it.instructionIndex }))
            ?: throw CaptureValidationException("MARKET_CAPTURE_MISMATCH")
        if (anchor.signature != window.untilSignature || anchor.slot.plus(1) != window.fromSlot) {
            throw CaptureValidationException("MARKET_CAPTURE_MISMATCH")
        }
        return anchor
    }
}
