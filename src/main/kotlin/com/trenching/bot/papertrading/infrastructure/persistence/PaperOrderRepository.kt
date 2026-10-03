package com.trenching.bot.papertrading.infrastructure.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface PaperOrderRepository : JpaRepository<PaperOrderEntity, String> {
    fun findByRunIdOrderByTargetSlotAscIdAsc(runId: String, pageable: Pageable): List<PaperOrderEntity>

    @Query("""
        select o.rejectionReason as reason, count(o) as count from PaperOrderEntity o
        where o.rejectionReason is not null group by o.rejectionReason
    """)
    fun countRejectionsByReason(): List<RejectionCountRow>
}
