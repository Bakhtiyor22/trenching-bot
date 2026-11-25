package com.trenching.bot.portfolio.domain

import com.trenching.bot.papertrading.domain.RejectionReason

/** What became of a run's positions: [closed] by why they were sold, [held] by why an open position could not be sold. */
data class PositionOutcomeCounts(val closed: Map<CloseReason, Int>, val held: Map<RejectionReason, Int>)
