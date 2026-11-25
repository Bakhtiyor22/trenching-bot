package com.trenching.bot.portfolio.infrastructure.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface PositionRepository : JpaRepository<PositionEntity, String> {
    fun findByRunIdOrderByTargetSlotAscIdAsc(runId: String, pageable: Pageable): List<PositionEntity>

    @Query("""
        select p.runId as runId, p.state as state, p.closeReason as closeReason, p.exitUnavailable as exitUnavailable, count(p) as count
        from PositionEntity p
        where p.runId in :runIds and (p.closeReason is not null or p.exitUnavailable is not null)
        group by p.runId, p.state, p.closeReason, p.exitUnavailable
    """)
    fun countOutcomesByRun(runIds: Collection<String>): List<PositionOutcomeRow>
}
