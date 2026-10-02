package com.trenching.bot.papertrading.infrastructure.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PaperOrderRepository : JpaRepository<PaperOrderEntity, String> {
    fun findByRunIdOrderByTargetSlotAscIdAsc(runId: String, pageable: Pageable): List<PaperOrderEntity>
}

