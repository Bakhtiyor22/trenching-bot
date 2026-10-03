package com.trenching.bot.replay.infrastructure.persistence

import java.time.Instant

/** Closed projection of `replay_datasets` without the snapshot text. */
interface ReplayDatasetSummaryRow {
    val id: String
    val name: String
    val trackedWallet: String
    val schemaVersion: Int
    val transactionCount: Int
    val observationCount: Int
    val firstSlot: Long?
    val lastSlot: Long?
    val pairs: String
    val createdAt: Instant
}
