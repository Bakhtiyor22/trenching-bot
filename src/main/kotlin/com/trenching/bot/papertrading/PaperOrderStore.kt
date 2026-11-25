package com.trenching.bot.papertrading

import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.PaperFill
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.strategy.domain.TradeIntent

interface PaperOrderStore {
    fun record(runId: String, intents: List<TradeIntent>, outcomes: List<FillOutcome>)
    fun outcomes(runId: String): List<FillOutcome>
    fun fills(runId: String): List<PaperFill>
    /** Rejected orders per reason across all runs; a reason that never occurred is absent. */
    fun rejectionCounts(): Map<RejectionReason, Long>
    /** Rejected orders with a stored reason per run and reason, for the given runs; a run with none is absent. */
    fun rejectionsByRun(runIds: Collection<String>): Map<String, Map<RejectionReason, Int>>
}
