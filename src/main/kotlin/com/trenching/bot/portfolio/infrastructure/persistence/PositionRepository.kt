package com.trenching.bot.portfolio.infrastructure.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PositionRepository : JpaRepository<PositionEntity, String> {
    fun findByRunIdOrderByTargetSlotAscIdAsc(runId: String, pageable: Pageable): List<PositionEntity>
}

