package com.trenching.bot.walletactivity.infrastructure.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface WalletEventRepository : JpaRepository<WalletEventEntity, String> {
    fun findByRunIdOrderBySlotAscSignatureAscInstructionIndexAsc(runId: String, pageable: Pageable): List<WalletEventEntity>
}
