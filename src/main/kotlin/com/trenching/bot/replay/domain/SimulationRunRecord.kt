package com.trenching.bot.replay.domain

import java.time.Instant

data class SimulationRunRecord(
    val id: String,
    val engineVersion: String,
    val datasetChecksum: String,
    val configurationHash: String,
    val trackedWallet: String,
    val configurationSnapshot: String,
    val datasetSnapshot: String,
    val reportSnapshot: String,
    val resultChecksum: String,
    val totals: RunTotals,
    val createdAt: Instant,
)
