package com.trenching.bot.replay.domain

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.walletactivity.domain.WalletTransaction

data class ReplayDataset(
    val schemaVersion: Int,
    val trackedWallet: WalletAddress,
    val transactions: List<WalletTransaction>,
    val observations: List<MarketObservation>,
)
