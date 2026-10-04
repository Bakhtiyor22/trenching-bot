package com.trenching.bot.replay.domain

import com.trenching.bot.common.WalletAddress
import java.nio.file.Path

/** A v1 dataset exported from recorded captures; [written] is false when [output] already held exactly this dataset. */
data class RecordedReplayExport(
    val output: Path,
    val checksum: String,
    val trackedWallet: WalletAddress,
    val sourceBuys: Int,
    val windows: Int,
    val transactions: Int,
    val observations: Int,
    val written: Boolean,
)
