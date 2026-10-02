package com.trenching.bot.papertrading.infrastructure.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PaperFillRepository : JpaRepository<PaperFillEntity, String> {
    fun findByRunIdOrderByActualSlotAscOrderIdAsc(runId: String, pageable: Pageable): List<PaperFillEntity>
}

