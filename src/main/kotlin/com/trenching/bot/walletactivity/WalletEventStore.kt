package com.trenching.bot.walletactivity

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.domain.*

interface WalletEventStore {
    fun record(runId: String, trackedWallet: WalletAddress, transactions: List<WalletTransaction>, outcomes: List<NormalizationOutcome>)
    fun transactions(runId: String): List<WalletTransaction>
    fun acceptedTrades(runId: String): List<WalletTradeObserved>
    fun ignored(runId: String): List<NormalizationOutcome.Ignored>
}
