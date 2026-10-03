package com.trenching.bot.console.api.dto

import java.time.Instant

data class DatasetResponse(
    val id: String,
    val name: String,
    val trackedWallet: String,
    val schemaVersion: Int,
    val transactionCount: Int,
    val observationCount: Int,
    val firstSlot: Long?,
    val lastSlot: Long?,
    val pairs: List<PairResponse>,
    val createdAt: Instant,
)

data class DatasetRegistrationResponse(
    val dataset: DatasetResponse,
    val created: Boolean,
)

data class DatasetPageResponse(
    val items: List<DatasetResponse>,
    val page: Int,
    val size: Int,
    val total: Long,
)
