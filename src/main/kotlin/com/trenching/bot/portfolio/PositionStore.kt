package com.trenching.bot.portfolio

import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.Position

interface PositionStore {
    fun record(positions: List<Position>, exitUnavailable: Map<String, RejectionReason>)
    fun positions(runId: String): List<Position>
}
