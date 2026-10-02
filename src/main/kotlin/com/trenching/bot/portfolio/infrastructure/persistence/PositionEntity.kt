package com.trenching.bot.portfolio.infrastructure.persistence

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.portfolio.domain.*
import com.trenching.bot.papertrading.domain.RejectionReason
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(
    name = "positions",
    indexes = [
        Index(name = "idx_positions_run_slot", columnList = "run_id, target_slot, id"),
    ],
)
class PositionEntity(
    @Id
    @Column(name = "id", nullable = false, length = 256)
    var id: String,
    @Column(name = "run_id", nullable = false, length = 64)
    var runId: String,
    @Column(name = "intent_id", nullable = false, length = 256)
    var intentId: String,
    @Column(name = "base_mint", nullable = false, length = 44)
    var baseMint: String,
    @Column(name = "quote_mint", nullable = false, length = 44)
    var quoteMint: String,
    @Column(name = "entry_offset", nullable = false)
    var entryOffset: Int,
    @Column(name = "fill_order_id", nullable = false, length = 256)
    var fillOrderId: String,
    @Column(name = "target_slot", nullable = false)
    var targetSlot: Long,
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    var state: PositionState,
    @Column(name = "close_slot", nullable = true)
    var closeSlot: Long?,
    @Column(name = "close_observation_id", nullable = true, length = 255)
    var closeObservationId: String?,
    @Column(name = "exit_price", nullable = true, precision = DecimalPolicy.PRICE_PRECISION, scale = DecimalPolicy.PRICE_SCALE)
    var exitPrice: BigDecimal?,
    @Column(name = "gross_proceeds", nullable = true, precision = 38, scale = 12)
    var grossProceeds: BigDecimal?,
    @Column(name = "exit_fee", nullable = true, precision = 38, scale = 12)
    var exitFee: BigDecimal?,
    @Column(name = "net_proceeds", nullable = true, precision = 38, scale = 12)
    var netProceeds: BigDecimal?,
    @Column(name = "realized_pnl", nullable = true, precision = 38, scale = 12)
    var realizedPnl: BigDecimal?,
    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", nullable = true, length = 32)
    var closeReason: CloseReason?,
    @Enumerated(EnumType.STRING)
    @Column(name = "exit_unavailable", nullable = true, length = 32)
    var exitUnavailable: RejectionReason?,
    @Version
    @Column(nullable = false)
    var version: Long? = null,
)

