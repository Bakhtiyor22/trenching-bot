package com.trenching.bot.console.domain

import com.trenching.bot.walletactivity.WalletActivityUnavailable
import java.time.Duration

class InvalidWalletAddressException(length: Int) : ConsoleException("Invalid wallet address length=$length")

class WalletActivityUnavailableException(val reason: WalletActivityUnavailable, val retryAfter: Duration?) :
    ConsoleException("Wallet activity unavailable reason=$reason") {
    /** The provider's `Retry-After` in whole seconds, rounded up, or null when it sent none. */
    val retryAfterSeconds: Long?
        get() = retryAfter?.let { if (it.toNanosPart() == 0) it.seconds else it.seconds + 1 }
}
