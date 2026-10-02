package com.trenching.bot.marketdata.infrastructure.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface MarketObservationRepository : JpaRepository<MarketObservationEntity, String> {
    fun findByRunIdOrderBySlotAscObservationIdAsc(runId: String, pageable: Pageable): List<MarketObservationEntity>
}
