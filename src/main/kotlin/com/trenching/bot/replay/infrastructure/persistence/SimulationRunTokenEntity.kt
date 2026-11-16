package com.trenching.bot.replay.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.io.Serializable

/** A token a run's dataset contains; [SimulationRunEntity] is referenced by id only, so a run's tokens outlive its dataset. */
@Entity
@Table(
    name = "simulation_run_tokens",
    indexes = [Index(name = "idx_simulation_run_tokens_mint", columnList = "mint, run_id")],
)
class SimulationRunTokenEntity(
    @EmbeddedId var id: SimulationRunTokenKey,
)

@Embeddable
data class SimulationRunTokenKey(
    @Column(name = "run_id", nullable = false, length = 64) val runId: String,
    @Column(name = "mint", nullable = false, length = 44) val mint: String,
) : Serializable
