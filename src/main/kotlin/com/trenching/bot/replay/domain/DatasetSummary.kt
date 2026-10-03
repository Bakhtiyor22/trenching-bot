package com.trenching.bot.replay.domain

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.Slot
import com.trenching.bot.common.WalletAddress
import java.time.Instant

/** A stored replay dataset; [id] is its `DatasetFingerprint` checksum, [name] only a label. */
data class DatasetSummary(
    val id: String,
    val name: String,
    val trackedWallet: WalletAddress,
    val schemaVersion: Int,
    val transactionCount: Int,
    val observationCount: Int,
    val firstSlot: Slot?,
    val lastSlot: Slot?,
    val pairs: List<MarketPair>,
    val createdAt: Instant,
)

data class DatasetRegistration(val dataset: DatasetSummary, val created: Boolean)
