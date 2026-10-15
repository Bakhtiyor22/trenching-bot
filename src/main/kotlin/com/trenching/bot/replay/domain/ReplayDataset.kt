package com.trenching.bot.replay.domain

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.walletactivity.domain.WalletTransaction

/**
 * Schema v3: [referenceRates] holds a SOL/USDT rate for the minute of every observation, and every observation its curve state.
 * Schema v4 adds each observation's decoded trade; a v3 dataset never carries one.
 * Schema v5 adds [coverage], the slots each market window was observed through; v3 and v4 datasets have none.
 */
data class ReplayDataset(
    val schemaVersion: Int,
    val trackedWallet: WalletAddress,
    val transactions: List<WalletTransaction>,
    val observations: List<MarketObservation>,
    val referenceRates: List<ReferenceRate>,
    val coverage: List<MarketCoverage> = emptyList(),
) {
    companion object {
        const val SCHEMA_V3 = 3
        const val SCHEMA_V4 = 4
        const val SCHEMA_V5 = 5

        /** The newest version the loader and writer support. */
        const val CURRENT_SCHEMA_VERSION = SCHEMA_V5

        /** The versions the loader reads and the runner replays. */
        val SUPPORTED_SCHEMA_VERSIONS: Set<Int> = setOf(SCHEMA_V3, SCHEMA_V4, SCHEMA_V5)

        fun recordsTrades(schemaVersion: Int): Boolean = schemaVersion >= SCHEMA_V4

        fun recordsCoverage(schemaVersion: Int): Boolean = schemaVersion >= SCHEMA_V5
    }
}
