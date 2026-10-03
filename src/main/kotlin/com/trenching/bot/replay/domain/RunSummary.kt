package com.trenching.bot.replay.domain

import com.trenching.bot.common.MintAddress
import java.math.BigDecimal
import java.time.Instant

/** A persisted run's identity and stored totals; [realizedPnl] is in units of [quoteMint]. */
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
)

data class RunStart(val run: RunSummary, val created: Boolean)
