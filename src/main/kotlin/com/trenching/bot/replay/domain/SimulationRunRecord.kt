package com.trenching.bot.replay.domain

import java.time.Instant

/** [datasetSnapshot] is null exactly when [storage] is [RunStorage.DATASET_REFERENCE]. */
data class SimulationRunRecord(
    val id: String,
    val engineVersion: String,
    val datasetChecksum: String,
    val configurationHash: String,
    val trackedWallet: String,
    val configurationSnapshot: String,
    val datasetSnapshot: String?,
    val reportSnapshot: String,
    val resultChecksum: String,
    val totals: RunTotals,
    val createdAt: Instant,
    val storage: RunStorage,
)
