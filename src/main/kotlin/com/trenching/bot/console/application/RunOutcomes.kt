package com.trenching.bot.console.application

import com.trenching.bot.console.domain.RunOutcome
import com.trenching.bot.console.domain.RunOutcomeStage
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.portfolio.PositionStore
import org.springframework.stereotype.Service

/** Why the tokens of the runs a response shows were sold, still held or not bought, from one call to each store. */
@Service
class RunOutcomes(private val orders: PaperOrderStore, private val positions: PositionStore) {
    /** An entry for every distinct id in [runIds], in their order, empty when nothing was stored with a reason; no call when there are none. */
    fun of(runIds: Collection<String>): Map<String, List<RunOutcome>> {
        val distinct = runIds.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val rejected = orders.rejectionsByRun(distinct)
        val positioned = positions.outcomesByRun(distinct)
        return distinct.associateWith { runId ->
            val counts = positioned[runId]
            val outcomes = counts?.closed.orEmpty().map { (reason, count) -> RunOutcome(reason.name, RunOutcomeStage.SOLD, count) } +
                counts?.held.orEmpty().map { (reason, count) -> RunOutcome(reason.name, RunOutcomeStage.HELD, count) } +
                rejected[runId].orEmpty().map { (reason, count) -> RunOutcome(reason.name, RunOutcomeStage.NOT_BOUGHT, count) }
            outcomes.sortedWith(compareBy<RunOutcome> { it.stage }.thenByDescending { it.count }.thenBy { it.code })
        }
    }

    fun of(runId: String): List<RunOutcome> = of(listOf(runId)).getValue(runId)
}
