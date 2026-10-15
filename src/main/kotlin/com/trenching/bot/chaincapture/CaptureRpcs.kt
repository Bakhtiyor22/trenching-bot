package com.trenching.bot.chaincapture

import java.time.Clock
import tools.jackson.databind.ObjectMapper

/** Builds the read-only RPC every capture uses, paced by the limiter of its endpoint and optionally backed by a transaction cache. */
object CaptureRpcs {
    fun create(
        settings: SolanaRpcSettings,
        mapper: ObjectMapper,
        clock: Clock,
        transactionCache: SharedTransactionCache? = null,
        limiters: RpcLimiterRegistry = RpcLimiterRegistry.shared,
    ): SolanaReadRpc {
        val http = HttpSolanaReadRpc(settings, mapper, clock, limiters.timing, limiters.limiterFor(settings))
        return if (transactionCache == null) http else CachingSolanaReadRpc(http, transactionCache)
    }
}
