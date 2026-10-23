package com.trenching.bot.strategy.infrastructure.persistence

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.strategy.domain.DipWatchOutcome
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(
    name = "trade_intents",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_trade_intents_scenario", columnNames = ["run_id", "strategy_name", "strategy_version", "source_event_id", "entry_offset"]),
    ],
    indexes = [
        Index(name = "idx_trade_intents_run_slot", columnList = "run_id, target_slot, id"),
    ],
)
class TradeIntentEntity(
    @Id
    @Column(name = "id", nullable = false, length = 256)
    var id: String,
    @Column(name = "run_id", nullable = false, length = 64)
    var runId: String,
    @Column(name = "source_event_id", nullable = false, length = 128)
    var sourceEventId: String,
    @Column(name = "base_mint", nullable = false, length = 44)
    var baseMint: String,
    @Column(name = "quote_mint", nullable = false, length = 44)
    var quoteMint: String,
    @Column(name = "strategy_name", nullable = false, length = 128)
    var strategyName: String,
    @Column(name = "strategy_version", nullable = false, length = 128)
    var strategyVersion: String,
    @Column(name = "configuration_hash", nullable = false, length = 64)
    var configurationHash: String,
    @Column(name = "entry_offset", nullable = false)
    var entryOffset: Int,
    @Column(name = "creation_slot", nullable = false)
    var creationSlot: Long,
    @Column(name = "t0", nullable = true)
    var t0: Instant?,
    @Column(name = "target_slot", nullable = false)
    var targetSlot: Long,
    @Column(name = "notional", nullable = false, precision = 38, scale = 12)
    var notional: BigDecimal,
    // Nullable only because `ddl-auto: update` cannot add NOT NULL columns to a populated table.
    // Every dip intent sets the first four; the trigger columns are set exactly when watch is TRIGGERED.
    @Column(name = "reference_price", nullable = true, precision = DecimalPolicy.PRICE_PRECISION, scale = DecimalPolicy.PRICE_SCALE)
    var referencePrice: BigDecimal?,
    @Column(name = "dip_price", nullable = true, precision = DecimalPolicy.PRICE_PRECISION, scale = DecimalPolicy.PRICE_SCALE)
    var dipPrice: BigDecimal?,
    @Column(name = "horizon_end_slot", nullable = true)
    var horizonEndSlot: Long?,
    @Enumerated(EnumType.STRING)
    @Column(name = "watch", nullable = true, length = 32)
    var watch: DipWatchOutcome?,
    @Column(name = "trigger_observation_id", nullable = true, length = 255)
    var triggerObservationId: String?,
    @Column(name = "trigger_slot", nullable = true)
    var triggerSlot: Long?,
    @Column(name = "trigger_price", nullable = true, precision = DecimalPolicy.PRICE_PRECISION, scale = DecimalPolicy.PRICE_SCALE)
    var triggerPrice: BigDecimal?,
    @Column(name = "trigger_market_cap_usd", nullable = true, precision = 38, scale = 12)
    var triggerMarketCapUsd: BigDecimal?,
    @Column(name = "trigger_rate", nullable = true, precision = DecimalPolicy.PRICE_PRECISION, scale = DecimalPolicy.PRICE_SCALE)
    var triggerRate: BigDecimal?,
    @Version
    @Column(nullable = false)
    var version: Long? = null,
)

