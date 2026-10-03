package com.trenching.bot.console.solana

import com.trenching.bot.common.WalletAddress

/** Read-only connectivity checks against the configured Solana RPC. Never sends a transaction. */
interface SolanaProbe {
    /** The RPC host, or null when no RPC is configured. The full URL is never exposed. */
    val host: String?

    fun check(): ProbeOutcome

    fun checkAddress(address: WalletAddress): AddressCheckOutcome
}
