package com.trenching.bot.portfolio.infrastructure.persistence

import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.PositionState

interface PositionOutcomeRow {
    val runId: String
    val state: PositionState
    val closeReason: CloseReason?
    val exitUnavailable: RejectionReason?
    val count: Long
}
