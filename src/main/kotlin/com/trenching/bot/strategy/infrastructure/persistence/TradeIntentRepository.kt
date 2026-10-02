package com.trenching.bot.strategy.infrastructure.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface TradeIntentRepository : JpaRepository<TradeIntentEntity, String> {
    fun findByRunIdOrderByTargetSlotAscIdAsc(runId: String, pageable: Pageable): List<TradeIntentEntity>
}

