package com.trenching.bot.walletactivity.infrastructure.persistence

import jakarta.persistence.*
import java.math.BigInteger
import java.time.Instant
import com.trenching.bot.common.Commitment
import com.trenching.bot.walletactivity.domain.TradeSide
import com.trenching.bot.walletactivity.domain.IngestionReason

@Entity
@Table(
    name = "wallet_events",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_wallet_events_identity", columnNames = ["run_id", "signature", "instruction_index"]),
        UniqueConstraint(name = "uq_wallet_events_event", columnNames = ["run_id", "event_id"]),
    ],
    indexes = [
        Index(name = "idx_wallet_events_run_slot", columnList = "run_id, slot, signature, instruction_index"),
    ],
)
class WalletEventEntity(
    @Id @Column(name = "id", nullable = false, length = 384) var id: String,
    @Column(name = "run_id", nullable = false, length = 64) var runId: String,
    @Column(name = "event_id", nullable = false, length = 128) var eventId: String,
    @Column(name = "tracked_wallet", nullable = false, length = 44) var trackedWallet: String,
    @Column(name = "wallet", nullable = false, length = 44) var wallet: String,
    @Column(name = "signature", nullable = false, length = 88) var signature: String,
    @Column(name = "instruction_index", nullable = false, columnDefinition = "integer") var instructionIndex: Int,
    @Column(name = "slot", nullable = false, columnDefinition = "bigint") var slot: Long,
    @Column(name = "block_time", nullable = true, columnDefinition = "timestamptz") var blockTime: Instant?,
    @Column(name = "base_mint", nullable = false, length = 44) var baseMint: String,
    @Column(name = "quote_mint", nullable = false, length = 44) var quoteMint: String,
    @Column(name = "base_delta", nullable = false, precision = 20, scale = 0) var baseDelta: BigInteger,
    @Column(name = "quote_delta", nullable = false, precision = 20, scale = 0) var quoteDelta: BigInteger,
    @Column(name = "base_decimals", nullable = false, columnDefinition = "integer") var baseDecimals: Int,
    @Column(name = "quote_decimals", nullable = false, columnDefinition = "integer") var quoteDecimals: Int,
    @Column(name = "successful", nullable = false, columnDefinition = "boolean") var successful: Boolean,
    @Column(name = "supported_route", nullable = false, columnDefinition = "boolean") var supportedRoute: Boolean,
    @Column(name = "signer", nullable = false, columnDefinition = "boolean") var signer: Boolean,
    @Column(name = "source", nullable = false, length = 128) var source: String,
    @Enumerated(EnumType.STRING) @Column(name = "commitment", nullable = false, length = 16) var commitment: Commitment,
    @Enumerated(EnumType.STRING) @Column(name = "side", nullable = true, length = 16) var side: TradeSide?,
    @Enumerated(EnumType.STRING) @Column(name = "ignored_reason", nullable = true, length = 32) var ignoredReason: IngestionReason?,
) {
    @Version @Column(name = "version", nullable = false)
    var version: Long? = null
}
