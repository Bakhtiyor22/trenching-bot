package com.trenching.bot.papertrading.infrastructure.persistence

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.papertrading.domain.*
import com.trenching.bot.marketdata.domain.ObservationQuality
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(
    name = "paper_fills",
    indexes = [
        Index(name = "idx_paper_fills_run_slot", columnList = "run_id, actual_slot, order_id"),
    ],
)
class PaperFillEntity(
    @Id
    @Column(name = "order_id", nullable = false, length = 256)
    var orderId: String,
    @Column(name = "run_id", nullable = false, length = 64)
    var runId: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 16)
    var orderStatus: OrderStatus,
    @Column(name = "target_slot", nullable = false)
    var targetSlot: Long,
    @Column(name = "actual_slot", nullable = false)
    var actualSlot: Long,
    @Column(name = "observation_id", nullable = false, length = 255)
    var observationId: String,
    @Column(name = "execution_price", nullable = false, precision = DecimalPolicy.PRICE_PRECISION, scale = DecimalPolicy.PRICE_SCALE)
    var executionPrice: BigDecimal,
    @Column(name = "quantity", nullable = false, precision = 38, scale = 12)
    var quantity: BigDecimal,
    @Column(name = "fee", nullable = false, precision = 38, scale = 12)
    var fee: BigDecimal,
    @Column(name = "cost_basis", nullable = false, precision = 38, scale = 12)
    var costBasis: BigDecimal,
    @Column(name = "priority_fee", nullable = false, precision = 38, scale = 12)
    var priorityFee: BigDecimal,
    @Column(name = "fee_bps", nullable = false)
    var feeBps: Int,
    @Column(name = "slippage_bps", nullable = false)
    var slippageBps: Int,
    @Column(name = "price_impact_bps", nullable = false)
    var priceImpactBps: Int,
    @Column(name = "price_source", nullable = false, length = 128)
    var priceSource: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "price_assumption", nullable = false, length = 32)
    var priceAssumption: ObservationQuality,
    @Version
    @Column(nullable = false)
    var version: Long? = null,
)

