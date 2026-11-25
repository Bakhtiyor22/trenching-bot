package com.trenching.bot.support

import com.trenching.bot.console.application.RunOutcomes
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.PaperFill
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.PositionStore
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.portfolio.domain.PositionOutcomeCounts
import com.trenching.bot.strategy.domain.TradeIntent

/** The two stores [RunOutcomes] reads, answering from maps and recording the run ids each was asked for. */
class StubOutcomeStores(
    val rejections: MutableMap<String, Map<RejectionReason, Int>> = mutableMapOf(),
    val positionCounts: MutableMap<String, PositionOutcomeCounts> = mutableMapOf(),
) : PaperOrderStore, PositionStore {
    val orderCalls = mutableListOf<List<String>>()
    val positionCalls = mutableListOf<List<String>>()

    fun outcomes(): RunOutcomes = RunOutcomes(this, this)

    override fun rejectionsByRun(runIds: Collection<String>): Map<String, Map<RejectionReason, Int>> {
        orderCalls += runIds.toList()
        return rejections.filterKeys { it in runIds }
    }

    override fun outcomesByRun(runIds: Collection<String>): Map<String, PositionOutcomeCounts> {
        positionCalls += runIds.toList()
        return positionCounts.filterKeys { it in runIds }
    }

    override fun record(runId: String, intents: List<TradeIntent>, outcomes: List<FillOutcome>) = error("unused")
    override fun outcomes(runId: String): List<FillOutcome> = error("unused")
    override fun fills(runId: String): List<PaperFill> = error("unused")
    override fun rejectionCounts(): Map<RejectionReason, Long> = error("unused")
    override fun record(positions: List<Position>, exitUnavailable: Map<String, RejectionReason>) = error("unused")
    override fun positions(runId: String): List<Position> = error("unused")
}
