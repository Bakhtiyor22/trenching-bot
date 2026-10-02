package com.trenching.bot.papertrading.infrastructure.persistence

import com.trenching.bot.papertrading.domain.*
import jakarta.persistence.*

@Entity
@Table(
    name = "paper_orders",
    indexes = [
        Index(name = "idx_paper_orders_run_slot", columnList = "run_id, target_slot, id"),
    ],
)
class PaperOrderEntity(
    @Id
    @Column(name = "id", nullable = false, length = 256)
    var id: String,
    @Column(name = "run_id", nullable = false, length = 64)
    var runId: String,
    @Column(name = "target_slot", nullable = false)
    var targetSlot: Long,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: OrderStatus,
    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", nullable = true, length = 32)
    var rejectionReason: RejectionReason?,
    @Version
    @Column(nullable = false)
    var version: Long? = null,
)

