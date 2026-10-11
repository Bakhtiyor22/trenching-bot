package com.trenching.bot.replay.domain

/** Whether a decoded wallet capture plans market windows; [Refused.reason] is the wallet stage's reason category. */
sealed interface MarketPlanDecision {
    data class Planned(val plan: ReplayMarketWindowPlan) : MarketPlanDecision

    data class Refused(val reason: String) : MarketPlanDecision
}
