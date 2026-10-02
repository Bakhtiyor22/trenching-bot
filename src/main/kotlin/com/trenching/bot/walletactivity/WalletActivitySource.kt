package com.trenching.bot.walletactivity

import com.trenching.bot.walletactivity.domain.WalletTransaction

interface WalletActivitySource {
    fun transactions(): List<WalletTransaction>
}
