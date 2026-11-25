package com.trenching.bot.portfolio

import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.portfolio.domain.PositionOutcomeCounts
import com.trenching.bot.portfolio.domain.StoredPosition

interface PositionStore {
    fun record(positions: List<Position>, exitUnavailable: Map<String, RejectionReason>)
    fun positions(runId: String): List<Position>
    fun storedPositions(runId: String): List<StoredPosition> = positions(runId).map { StoredPosition(it, null) }
    /** Closed positions by close reason and open ones by why they stayed open, per run; a run with neither is absent. */
    fun outcomesByRun(runIds: Collection<String>): Map<String, PositionOutcomeCounts>
}
