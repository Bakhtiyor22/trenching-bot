package com.trenching.bot.chaincapture

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * The [ProviderRequestLimiter] of each RPC endpoint, so every capture against one endpoint paces together. The endpoint,
 * trimmed of whitespace and a trailing slash, is kept only as a hash, as it can embed an API key. Production code shares [shared]; tests build their own registry.
 */
class RpcLimiterRegistry(val timing: CaptureTiming = SystemCaptureTiming) {
    private val log = LoggerFactory.getLogger(RpcLimiterRegistry::class.java)
    private val entries = ConcurrentHashMap<String, Entry>()

    /** The limiter for [settings]' endpoint; the settings of the first caller fix its pacing and later differences are logged. */
    fun limiterFor(settings: SolanaRpcSettings): ProviderRequestLimiter {
        val entry = entries.computeIfAbsent(Sha256.hex(settings.endpoint.orEmpty().trim().trimEnd('/').toByteArray())) {
            Entry(settings.maxConcurrentRequests, settings.minRequestInterval, ProviderRequestLimiter.of(settings, timing))
        }
        if (entry.maxConcurrentRequests != settings.maxConcurrentRequests || entry.minRequestInterval != settings.minRequestInterval) {
            log.warn("chain_capture limiter_settings_ignored maxConcurrentRequests={} minRequestIntervalMillis={} requestedMaxConcurrentRequests={} requestedMinRequestIntervalMillis={}",
                entry.maxConcurrentRequests, entry.minRequestInterval.toMillis(), settings.maxConcurrentRequests, settings.minRequestInterval.toMillis())
        }
        return entry.limiter
    }

    private class Entry(val maxConcurrentRequests: Int, val minRequestInterval: Duration, val limiter: ProviderRequestLimiter)

    companion object {
        /** The registry of this process: console workers and CLI commands all pace through it. */
        val shared = RpcLimiterRegistry()
    }
}
