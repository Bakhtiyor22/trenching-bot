package com.trenching.bot.replay.domain

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.Slot

/** A scenario of [pair] decided at [decidedAt], after its coverage ends at [throughSlot]; null when the pair has no coverage. */
data class CoverageShortfall(
    val pair: MarketPair,
    val intentId: String,
    val decidedAt: Slot,
    val throughSlot: Slot?,
)
