package com.trenching.bot.replay.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SimulationRunTokenRepository : JpaRepository<SimulationRunTokenEntity, SimulationRunTokenKey> {
    @Query("select t.id.runId as runId, t.id.mint as mint from SimulationRunTokenEntity t where t.id.runId in :runIds")
    fun findAllByRunIds(@Param("runIds") runIds: Collection<String>): List<SimulationRunTokenRow>
}

interface SimulationRunTokenRow {
    val runId: String
    val mint: String
}
