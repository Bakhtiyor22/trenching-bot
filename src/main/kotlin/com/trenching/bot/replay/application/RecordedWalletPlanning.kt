package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.common.MintAddress
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.replay.domain.MarketPlanDecision
import com.trenching.bot.replay.domain.PlannedWalletHistory
import com.trenching.bot.walletactivity.RecordedWalletHistory
import com.trenching.bot.walletactivity.WalletHistoryDecodeResult
import com.trenching.bot.walletactivity.application.WalletNormalizer
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import com.trenching.bot.walletactivity.domain.TradeSide
import com.trenching.bot.walletactivity.domain.WalletTransaction
import com.trenching.bot.walletactivity.domain.WalletTradeObserved
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Verifies a recorded wallet capture is complete, decodes it and plans the market windows of the buys the strategy accepts,
 * with the lead its caller passes: a market capture is checked against windows planned with the lead it recorded, so one
 * made before the lead keeps its `S + 1` windows. [planForMarketCapture] holds the rule for a capture that may not exist yet.
 */
class RecordedWalletPlanning(
    private val walletHistory: RecordedWalletHistory,
    planner: ReplayMarketWindowPlanner = ReplayMarketWindowPlanner(),
) {
    private val log = LoggerFactory.getLogger(RecordedWalletPlanning::class.java)
    private val gate = MarketPlanGate(planner)
    private val normalizer = WalletNormalizer()

    fun plan(walletCaptureDirectory: Path, leadSlots: Long): PlannedWalletHistory = plan(history(walletCaptureDirectory), leadSlots)

    /** The complete wallet capture, decoded; it is verified before any market capture is read. */
    fun history(walletCaptureDirectory: Path): WalletHistoryDecodeResult {
        val captured = walletHistory.inspect(walletCaptureDirectory)
        if (!captured.manifest.complete) throw CaptureValidationException("WALLET_CAPTURE_INCOMPLETE")
        return walletHistory.decode(walletCaptureDirectory, WalletAddress.of(captured.manifest.wallet))
    }

    /**
     * Plans with the lead of [saved], the window parameters a market capture's manifest records, or with the default lead
     * when there is no manifest yet and a new capture would start.
     */
    fun planForMarketCapture(history: WalletHistoryDecodeResult, saved: MarketCaptureWindowParameters?): PlannedWalletHistory =
        plan(history, saved?.leadSlots ?: MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS)

    fun plan(history: WalletHistoryDecodeResult, leadSlots: Long): PlannedWalletHistory {
        val plan = when (val decision = gate.plan(history, leadSlots)) {
            is MarketPlanDecision.Planned -> decision.plan
            is MarketPlanDecision.Refused -> {
                log.warn("replay_market_plan refused reason={} wallet={}", decision.reason, history.wallet.value)
                throw CaptureValidationException(decision.reason)
            }
        }
        log.info("replay_market_plan sourceBuys={} windows={} horizonSlots={} maxAgeSlots={} leadSlots={}",
            plan.sourceBuys, plan.windows.size, plan.parameters.horizonSlots, plan.parameters.maxAgeSlots, plan.parameters.leadSlots)
        return PlannedWalletHistory(history, plan)
    }

    /** Resolves a planned window to the exact accepted first-buy instruction that anchors it under the plan's parameters. */
    fun anchor(planned: PlannedWalletHistory, window: MarketCaptureWindow): WalletTradeObserved {
        if (window !in planned.plan.windows) throw CaptureValidationException("MARKET_CAPTURE_MISMATCH")
        val anchor = planned.history.transactions.map { normalizer.normalize(it, planned.history.wallet, MintAddress.WRAPPED_SOL) }
            .filterIsInstance<NormalizationOutcome.Accepted>()
            .map { it.trade }
            .filter { it.side == TradeSide.BUY && it.pair.base == window.baseMint }
            .minWithOrNull(compareBy({ it.slot.value }, { it.signature.value }, { it.instructionIndex }))
            ?: throw CaptureValidationException("MARKET_CAPTURE_MISMATCH")
        if (window != MarketCaptureWindow.forFirstBuy(anchor.pair.base, anchor.slot, anchor.signature, planned.plan.parameters)) {
            throw CaptureValidationException("MARKET_CAPTURE_MISMATCH")
        }
        return anchor
    }

    /** The wallet transactions of a window's anchor buy that trade its mint: all a replay of that window's cycle needs. */
    fun anchorTransactions(planned: PlannedWalletHistory, window: MarketCaptureWindow): List<WalletTransaction> {
        val anchor = anchor(planned, window)
        return planned.history.transactions.filter { it.signature == anchor.signature && it.pair.base == window.baseMint }
    }
}
