package com.trenching.bot.strategy.domain

/** How a dip cycle's watch ended: [trigger] is set only for [DipWatchOutcome.TRIGGERED], [walletSell] only for [DipWatchOutcome.WALLET_SOLD]. */
data class DipWatchResult(
    val outcome: DipWatchOutcome,
    val trigger: DipTrigger? = null,
    val walletSell: WalletSellSignal? = null,
)
