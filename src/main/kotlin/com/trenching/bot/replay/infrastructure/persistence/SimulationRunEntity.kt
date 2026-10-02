package com.trenching.bot.replay.infrastructure.persistence

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "simulation_runs",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_simulation_runs_identity", columnNames = ["engine_version", "dataset_checksum", "configuration_hash"]),
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
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
) {
    @Version @Column(name = "version", nullable = false)
    var version: Long? = null
}
