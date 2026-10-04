package com.trenching.bot.replay.domain

import com.trenching.bot.walletactivity.WalletHistoryDecodeResult

/** A complete recorded wallet capture, decoded, with the market windows its accepted buys need. */
data class PlannedWalletHistory(
    val history: WalletHistoryDecodeResult,
    val plan: ReplayMarketWindowPlan,
)
