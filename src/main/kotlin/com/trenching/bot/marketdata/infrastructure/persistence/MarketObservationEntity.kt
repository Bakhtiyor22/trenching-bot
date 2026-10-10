package com.trenching.bot.marketdata.infrastructure.persistence

import jakarta.persistence.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import com.trenching.bot.common.Commitment
import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.marketdata.domain.ObservationQuality

@Entity
@Table(
    name = "market_observations",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_market_observations_identity", columnNames = ["run_id", "observation_id"]),
    ],
    indexes = [
        Index(name = "idx_market_observations_pair_slot", columnList = "run_id, base_mint, quote_mint, slot, observation_id"),
    ],
)
class MarketObservationEntity(
    @Id @Column(name = "id", nullable = false, length = 384) var id: String,
    @Column(name = "run_id", nullable = false, length = 64) var runId: String,
    @Column(name = "observation_id", nullable = false, length = 255) var observationId: String,
    @Column(name = "base_mint", nullable = false, length = 44) var baseMint: String,
    @Column(name = "quote_mint", nullable = false, length = 44) var quoteMint: String,
    @Column(name = "slot", nullable = false, columnDefinition = "bigint") var slot: Long,
    @Column(name = "price_slot", nullable = false, columnDefinition = "bigint") var priceSlot: Long,
    @Column(name = "observed_time", nullable = false, columnDefinition = "timestamptz") var observedTime: Instant,
    @Column(name = "price", nullable = false, precision = DecimalPolicy.PRICE_PRECISION, scale = DecimalPolicy.PRICE_SCALE) var price: BigDecimal,
    @Column(name = "price_scale", nullable = false, columnDefinition = "integer") var priceScale: Int,
    @Column(name = "liquidity_quote", nullable = true, precision = DecimalPolicy.PRICE_PRECISION, scale = DecimalPolicy.PRICE_SCALE) var liquidityQuote: BigDecimal?,
    @Column(name = "liquidity_scale", nullable = true, columnDefinition = "integer") var liquidityScale: Int?,
    @Column(name = "source", nullable = false, length = 128) var source: String,
    @Column(name = "source_event_id", nullable = false, length = 255) var sourceEventId: String,
    @Enumerated(EnumType.STRING) @Column(name = "commitment", nullable = false, length = 16) var commitment: Commitment,
    @Enumerated(EnumType.STRING) @Column(name = "quality", nullable = false, length = 32) var quality: ObservationQuality,
    // Nullable only because `ddl-auto: update` cannot add NOT NULL columns to a populated table.
    @Column(name = "curve_complete", nullable = true, columnDefinition = "boolean") var curveComplete: Boolean?,
    @Column(name = "virtual_sol_reserves", nullable = true, precision = 20, scale = 0) var virtualSolReserves: BigInteger?,
    @Column(name = "virtual_token_reserves", nullable = true, precision = 20, scale = 0) var virtualTokenReserves: BigInteger?,
    @Column(name = "real_sol_reserves", nullable = true, precision = 20, scale = 0) var realSolReserves: BigInteger?,
    @Column(name = "real_token_reserves", nullable = true, precision = 20, scale = 0) var realTokenReserves: BigInteger?,
    @Column(name = "fee_bps", nullable = true, columnDefinition = "integer") var feeBps: Int?,
    @Column(name = "creator_fee_bps", nullable = true, columnDefinition = "integer") var creatorFeeBps: Int?,
) {
    @Version @Column(name = "version", nullable = false)
    var version: Long? = null
}
