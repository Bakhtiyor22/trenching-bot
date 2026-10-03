package com.trenching.bot.replay.infrastructure.persistence

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(
    name = "simulation_runs",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_simulation_runs_identity", columnNames = ["engine_version", "dataset_checksum", "configuration_hash"]),
    ],
    indexes = [
        Index(name = "idx_simulation_runs_created_at", columnList = "created_at, id"),
        Index(name = "idx_simulation_runs_dataset", columnList = "dataset_checksum, created_at"),
    ],
)
class SimulationRunEntity(
    @Id @Column(name = "id", nullable = false, length = 64) var id: String,
    @Column(name = "engine_version", nullable = false, length = 64) var engineVersion: String,
    @Column(name = "dataset_checksum", nullable = false, length = 64) var datasetChecksum: String,
    @Column(name = "configuration_hash", nullable = false, length = 64) var configurationHash: String,
    @Column(name = "tracked_wallet", nullable = false, length = 44) var trackedWallet: String,
    @Column(name = "configuration_snapshot", nullable = false, columnDefinition = "text") var configurationSnapshot: String,
    @Column(name = "dataset_snapshot", nullable = false, columnDefinition = "text") var datasetSnapshot: String,
    @Column(name = "report_snapshot", nullable = false, columnDefinition = "text") var reportSnapshot: String,
    @Column(name = "result_checksum", nullable = false, length = 64) var resultChecksum: String,
    @Column(name = "quote_mint", nullable = false, length = 44) var quoteMint: String,
    @Column(name = "scenario_count", nullable = false) var scenarioCount: Int,
    @Column(name = "filled_count", nullable = false) var filledCount: Int,
    @Column(name = "rejected_count", nullable = false) var rejectedCount: Int,
    @Column(name = "open_count", nullable = false) var openCount: Int,
    @Column(name = "closed_count", nullable = false) var closedCount: Int,
    @Column(name = "realized_pnl", nullable = false, precision = 38, scale = 12) var realizedPnl: BigDecimal,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
) {
    @Version @Column(name = "version", nullable = false)
    var version: Long? = null
}
