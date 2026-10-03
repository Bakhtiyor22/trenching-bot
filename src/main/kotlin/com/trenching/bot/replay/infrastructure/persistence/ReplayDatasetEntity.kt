package com.trenching.bot.replay.infrastructure.persistence

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "replay_datasets",
    indexes = [Index(name = "idx_replay_datasets_created_at", columnList = "created_at, id")],
)
class ReplayDatasetEntity(
    @Id @Column(name = "id", nullable = false, length = 64) var id: String,
    @Column(name = "name", nullable = false, length = 128) var name: String,
    @Column(name = "tracked_wallet", nullable = false, length = 44) var trackedWallet: String,
    @Column(name = "schema_version", nullable = false) var schemaVersion: Int,
    @Column(name = "transaction_count", nullable = false) var transactionCount: Int,
    @Column(name = "observation_count", nullable = false) var observationCount: Int,
    @Column(name = "first_slot") var firstSlot: Long?,
    @Column(name = "last_slot") var lastSlot: Long?,
    @Column(name = "pairs", nullable = false, columnDefinition = "text") var pairs: String,
    @Column(name = "snapshot", nullable = false, columnDefinition = "text") var snapshot: String,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
) {
    @Version @Column(name = "version", nullable = false)
    var version: Long? = null
}
