package com.trenching.bot.console.solana

import com.trenching.bot.common.WalletAddress
import java.time.Instant

data class AddressProbeCheck(
    val address: WalletAddress,
    val outcome: AddressCheckOutcome,
    val checkedAt: Instant,
    val cached: Boolean,
)
