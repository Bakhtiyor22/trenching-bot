package com.trenching.bot.portfolio

import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.portfolio.domain.StoredPosition

interface PositionStore {
    fun record(positions: List<Position>, exitUnavailable: Map<String, RejectionReason>)
    fun positions(runId: String): List<Position>
    fun storedPositions(runId: String): List<StoredPosition> = positions(runId).map { StoredPosition(it, null) }
}
