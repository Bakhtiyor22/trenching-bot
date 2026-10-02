package com.trenching.bot.replay.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface SimulationRunRepository : JpaRepository<SimulationRunEntity, String> {
    fun findOneById(id: String): SimulationRunEntity?
}
