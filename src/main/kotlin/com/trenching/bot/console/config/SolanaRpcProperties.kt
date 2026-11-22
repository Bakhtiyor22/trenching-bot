package com.trenching.bot.console.config

import com.trenching.bot.chaincapture.RpcEndpointRules
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Not a data class: a generated toString would print [rpcUrl] and [backupRpcUrl], which may embed an API key. A failed check names the
 * property and never quotes a value.
 */
@ConfigurationProperties("trenching.solana")
class SolanaRpcProperties(
    val rpcUrl: String? = null,
    val backupRpcUrl: String? = null,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(10),
    val cacheTtl: Duration = Duration.ofSeconds(30),
    val usage: SolanaUsageProperties = SolanaUsageProperties(),
) {
    init {
        require(connectTimeout.isPositive) { "trenching.solana.connect-timeout must be positive" }
        require(readTimeout.isPositive) { "trenching.solana.read-timeout must be positive" }
        require(!cacheTtl.isNegative) { "trenching.solana.cache-ttl must not be negative" }
        requireUsable("trenching.solana.rpc-url", rpcUrl)
        requireUsable("trenching.solana.backup-rpc-url", backupRpcUrl)
        require(backupRpcUrl.isNullOrBlank() || !rpcUrl.isNullOrBlank()) {
            "trenching.solana.backup-rpc-url needs trenching.solana.rpc-url: a backup account without a primary is not supported"
        }
        require(backupRpcUrl.isNullOrBlank() || backupRpcUrl.trim() != rpcUrl?.trim()) {
            "trenching.solana.backup-rpc-url must differ from trenching.solana.rpc-url"
        }
    }

    private fun requireUsable(property: String, value: String?) {
        val violation = value?.trim()?.takeIf { it.isNotEmpty() }?.let(RpcEndpointRules::violation) ?: return
        throw IllegalArgumentException("$property ${meaning(violation)}")
    }

    private fun meaning(violation: String): String =
        if (violation == RpcEndpointRules.NOT_TLS) "must use https unless it points to this machine"
        else "must be an absolute http(s) URL with a host, no user-info and no fragment"

    override fun toString(): String = "SolanaRpcProperties(primaryConfigured=${!rpcUrl.isNullOrBlank()}, backupConfigured=${!backupRpcUrl.isNullOrBlank()})"
}
