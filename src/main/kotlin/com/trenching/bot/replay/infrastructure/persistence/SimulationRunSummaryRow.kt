package com.trenching.bot.replay.infrastructure.persistence

import java.math.BigDecimal
import java.time.Instant

/** Closed projection of `simulation_runs` without the snapshot texts. */
interface SimulationRunSummaryRow {
    val id: String
    val engineVersion: String
    val datasetChecksum: String
    val configurationHash: String
    val resultChecksum: String
    val quoteMint: String
    val scenarioCount: Int
    val filledCount: Int
    val rejectedCount: Int
    val openCount: Int
    val closedCount: Int
    val realizedPnl: BigDecimal
    val createdAt: Instant
}
