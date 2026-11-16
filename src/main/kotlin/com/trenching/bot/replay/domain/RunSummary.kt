package com.trenching.bot.replay.domain

import com.trenching.bot.common.MintAddress
import java.math.BigDecimal
import java.time.Instant

/**
 * A persisted run's identity and stored totals; [realizedPnl] is in units of [quoteMint]. [datasetName] is null when the
 * dataset is no longer in the catalog, [mints] is empty and [anchorBlockTime] null while the run is not searchable by them,
 * and [mints] is sorted.
 */
data class RunSummary(
    val id: String,
    val datasetId: String,
    val engineVersion: String,
    val configurationHash: String,
    val resultChecksum: String,
    val quoteMint: MintAddress,
    val scenarioCount: Int,
    val filledCount: Int,
    val rejectedCount: Int,
    val openCount: Int,
    val closedCount: Int,
    val realizedPnl: BigDecimal,
    val createdAt: Instant,
    val storage: RunStorage,
    val datasetName: String? = null,
    val mints: List<MintAddress> = emptyList(),
    val anchorBlockTime: Instant? = null,
) {
    val status: RunStatus get() = RunStatus.of(filledCount, openCount, realizedPnl)
}

data class RunStart(val run: RunSummary, val created: Boolean)
