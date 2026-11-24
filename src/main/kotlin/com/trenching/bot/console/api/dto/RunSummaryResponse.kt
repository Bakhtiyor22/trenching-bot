package com.trenching.bot.console.api.dto

import java.time.Instant

/**
 * [realizedPnl] is an exact decimal string in units of [quoteMint]. [storage] is `COPY` (the run keeps its own dataset copy)
 * or `DATASET_REFERENCE` (it reads the catalog dataset). [status] is `PROFIT`, `LOSS`, `FLAT`, `NO_ENTRY` or `OPEN`;
 * [datasetName] is null when the dataset left the catalog; [mints] is sorted and empty when unknown; [anchorBlockTime] is the
 * dataset's anchor buy time, if it has one. [variations] are the labels of the configured sweep plan whose sweep produced the run,
 * by number; empty when none did, and never null.
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
    val status: String,
    val datasetName: String?,
    val mints: List<String>,
    val anchorBlockTime: Instant?,
    val variations: List<String>,
)

data class RunStartResponse(
    val run: RunSummaryResponse,
    val created: Boolean,
)

/** [asOf] is the snapshot of the page. [statusCounts] has every status and counts with every filter except the status. */
data class RunPageResponse(
    val items: List<RunSummaryResponse>,
    val page: Int,
    val size: Int,
    val total: Long,
    val asOf: Instant,
    val statusCounts: Map<String, Long>,
)
