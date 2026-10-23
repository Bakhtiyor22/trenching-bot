package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.MintAddress
import com.trenching.bot.marketdata.MarketCaptureReasons
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.replay.domain.MarketPlanDecision
import com.trenching.bot.replay.domain.ReplayMarketWindowPlan
import com.trenching.bot.replay.domain.TargetFirstBuyAssessment
import com.trenching.bot.replay.domain.TargetFirstBuyGate
import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.WalletHistoryDecodeResult
import org.slf4j.LoggerFactory

/**
 * Plans the market windows of a decoded wallet capture, each from [plan]'s `leadSlots` before its first buy; a first buy too
 * early for that lead refuses the plan. A wallet + token capture plans only when [TargetFirstBuyGate] passes, and then
 * exactly one window, `[B - lead, B + horizon]` around its proven first buy B; the existing scopes plan every mint's first
 * accepted buy.
 */
internal class MarketPlanGate(private val planner: ReplayMarketWindowPlanner = ReplayMarketWindowPlanner()) {
    private val log = LoggerFactory.getLogger(MarketPlanGate::class.java)

    fun plan(history: WalletHistoryDecodeResult, leadSlots: Long): MarketPlanDecision {
        if (!planner.leadFits(history, leadSlots = leadSlots)) return MarketPlanDecision.Refused(MarketCaptureReasons.MARKET_WINDOW_INVALID)
        val target = history.targetToken ?: return planned(planner.plan(history, leadSlots = leadSlots))
        return when (val assessment = TargetFirstBuyGate.assess(target)) {
            is TargetFirstBuyAssessment.Refused -> MarketPlanDecision.Refused(assessment.reason.name)
            is TargetFirstBuyAssessment.Proven ->
                MarketPlanDecision.Planned(targetPlan(history, target.mint, assessment.firstBuy, leadSlots))
        }
    }

    private fun planned(plan: ReplayMarketWindowPlan): MarketPlanDecision =
        if (plan.windows.isEmpty()) MarketPlanDecision.Refused(NO_SOURCE_BUYS) else MarketPlanDecision.Planned(plan)

    /** The decoded buys must start the strategy's only cycle at the proven first buy, or the capture contradicts itself. */
    private fun targetPlan(
        history: WalletHistoryDecodeResult,
        mint: MintAddress,
        firstBuy: ActualFirstBuy.Proven,
        leadSlots: Long,
    ): ReplayMarketWindowPlan {
        val plan = planner.plan(history, leadSlots = leadSlots)
        val expected = MarketCaptureWindow.forFirstBuy(mint, firstBuy.slot, firstBuy.signature, plan.parameters)
        if (plan.windows != listOf(expected)) {
            log.warn("replay_market_plan refused reason={} mint={} firstBuySignature={} firstBuySlot={} plannedWindows={}",
                TARGET_WINDOW_MISMATCH, mint.value, firstBuy.signature.value, firstBuy.slot.value, plan.windows.size)
            throw CaptureValidationException(TARGET_WINDOW_MISMATCH)
        }
        return plan
    }

    private companion object {
        const val NO_SOURCE_BUYS = "NO_SOURCE_BUYS"
        const val TARGET_WINDOW_MISMATCH = "TARGET_WINDOW_MISMATCH"
    }
}
