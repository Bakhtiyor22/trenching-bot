package com.trenching.bot.replay.domain

import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters

/** Market capture windows for a wallet capture's accepted buys; [sourceBuys] counts the buys before merging. */
data class ReplayMarketWindowPlan(
    val windows: List<MarketCaptureWindow>,
    val parameters: MarketCaptureWindowParameters,
    val sourceBuys: Int,
)
