package com.trenching.bot.strategy.infrastructure.persistence

import jakarta.persistence.*
import java.math.BigDecimal

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
    @Column(name = "target_slot", nullable = false)
    var targetSlot: Long,
    @Column(name = "notional", nullable = false, precision = 38, scale = 12)
    var notional: BigDecimal,
    @Version
    @Column(nullable = false)
    var version: Long? = null,
)

