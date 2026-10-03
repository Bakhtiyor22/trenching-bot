package com.trenching.bot.console.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/** Not a data class: a generated toString would print [rpcUrl], which may embed an API key. */
@ConfigurationProperties("trenching.solana")
class SolanaRpcProperties(
    val rpcUrl: String? = null,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(10),
    val cacheTtl: Duration = Duration.ofSeconds(30),
) {
    init {
        require(connectTimeout.isPositive) { "trenching.solana.connect-timeout must be positive" }
        require(readTimeout.isPositive) { "trenching.solana.read-timeout must be positive" }
        require(!cacheTtl.isNegative) { "trenching.solana.cache-ttl must not be negative" }
    }
}
