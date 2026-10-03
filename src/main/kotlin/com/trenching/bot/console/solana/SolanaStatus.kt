package com.trenching.bot.console.solana

/** [lastCheck] is marked cached while it is still inside the cache TTL, i.e. a check now would return it. */
data class SolanaStatus(val configured: Boolean, val host: String?, val lastCheck: ProbeCheck?)
