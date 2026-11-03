package com.trenching.bot.console.api.dto

import java.time.Instant

/**
 * [realizedPnl] is an exact decimal string in units of [quoteMint]. [storage] is `COPY` (the run keeps its own dataset copy)
 * or `DATASET_REFERENCE` (it reads the catalog dataset).
 */
data class RunSummaryResponse(
    val id: String,
    val datasetId: String,
    val engineVersion: String,
    val configurationHash: String,
    val resultChecksum: String,
    val quoteMint: String,
    val scenarioCount: Int,
    val filledCount: Int,
    val rejectedCount: Int,
    val openCount: Int,
    val closedCount: Int,
    val realizedPnl: String,
    val createdAt: Instant,
    val storage: String,
)

data class RunStartResponse(
    val run: RunSummaryResponse,
    val created: Boolean,
)

data class RunPageResponse(
    val items: List<RunSummaryResponse>,
    val page: Int,
    val size: Int,
    val total: Long,
)
