package com.trenching.bot.replay.application

import com.trenching.bot.replay.domain.RunSearchIndex
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunTokenEntity
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunTokenKey
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunTokenRepository
import org.springframework.stereotype.Component

/** Stores the tokens of runs' search indexes. Idempotent: a token a run already holds is left as it is. */
@Component
class RunSearchIndexWriter(private val tokens: SimulationRunTokenRepository) {
    fun storeTokens(indexes: Map<String, RunSearchIndex>) {
        if (indexes.isEmpty()) return
        val held = tokens.findAllByRunIds(indexes.keys).map { SimulationRunTokenKey(it.runId, it.mint) }.toSet()
        val missing = indexes.flatMap { (runId, index) -> index.mints.map { SimulationRunTokenKey(runId, it.value) } }
            .filterNot { it in held }
        tokens.saveAll(missing.map(::SimulationRunTokenEntity))
    }
}
