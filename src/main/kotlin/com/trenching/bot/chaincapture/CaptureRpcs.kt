package com.trenching.bot.chaincapture

import com.trenching.bot.apiusage.ApiUsageRecorder
import java.time.Clock
import tools.jackson.databind.ObjectMapper

/**
 * Builds the read-only RPC every capture uses, paced by the limiter of its endpoint, optionally backed by a transaction
 * cache, and recording its requests and cache hits as API usage.
 */
object CaptureRpcs {
    fun create(
        settings: SolanaRpcSettings,
        mapper: ObjectMapper,
        clock: Clock,
        transactionCache: SharedTransactionCache? = null,
        limiters: RpcLimiterRegistry = RpcLimiterRegistry.shared,
        recorder: ApiUsageRecorder = ApiUsageRecorder.NONE,
    ): SolanaReadRpc {
        val http = HttpSolanaReadRpc(settings, mapper, clock, limiters.timing, limiters.limiterFor(settings), recorder)
        return if (transactionCache == null) http else CachingSolanaReadRpc(http, transactionCache, recorder)
    }
}
