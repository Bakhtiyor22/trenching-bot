package com.trenching.bot.replay.domain

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.walletactivity.domain.WalletTransaction

/** Schema v3: [referenceRates] holds a SOL/USDT rate for the minute of every observation, and every observation its curve state. */
data class ReplayDataset(
    val schemaVersion: Int,
    val trackedWallet: WalletAddress,
    val transactions: List<WalletTransaction>,
    val observations: List<MarketObservation>,
    val referenceRates: List<ReferenceRate>,
) {
    companion object {
        const val SCHEMA_VERSION = 3
    }
}
