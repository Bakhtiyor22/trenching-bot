package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.MintAddress
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.replay.domain.MarketPlanDecision
import com.trenching.bot.replay.domain.ReplayMarketWindowPlan
import com.trenching.bot.replay.domain.TargetFirstBuyAssessment
import com.trenching.bot.replay.domain.TargetFirstBuyGate
import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.WalletHistoryDecodeResult
import org.slf4j.LoggerFactory

/**
 * Plans the market windows of a decoded wallet capture. A wallet + token capture plans only when [TargetFirstBuyGate] passes,
 * and then exactly one window, `[B + 1, B + horizon]` after its proven first buy B; the existing scopes plan every mint's
 * first accepted buy.
 */
internal class MarketPlanGate(private val planner: ReplayMarketWindowPlanner = ReplayMarketWindowPlanner()) {
    private val log = LoggerFactory.getLogger(MarketPlanGate::class.java)

    fun plan(history: WalletHistoryDecodeResult): MarketPlanDecision {
        val target = history.targetToken ?: return planned(planner.plan(history))
        return when (val assessment = TargetFirstBuyGate.assess(target)) {
            is TargetFirstBuyAssessment.Refused -> MarketPlanDecision.Refused(assessment.reason.name)
            is TargetFirstBuyAssessment.Proven -> MarketPlanDecision.Planned(targetPlan(history, target.mint, assessment.firstBuy))
        }
    }

    private fun planned(plan: ReplayMarketWindowPlan): MarketPlanDecision =
        if (plan.windows.isEmpty()) MarketPlanDecision.Refused(NO_SOURCE_BUYS) else MarketPlanDecision.Planned(plan)

    /** The decoded buys must start the strategy's only cycle at the proven first buy, or the capture contradicts itself. */
    private fun targetPlan(history: WalletHistoryDecodeResult, mint: MintAddress, firstBuy: ActualFirstBuy.Proven): ReplayMarketWindowPlan {
        val plan = planner.plan(history)
        val expected = MarketCaptureWindow.of(mint, firstBuy.slot.plus(1), firstBuy.slot.plus(plan.parameters.horizonSlots), firstBuy.signature)
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
