package com.trenching.bot.chaincapture

import com.trenching.bot.apiusage.ApiUsageRecorder
import java.time.Clock
import tools.jackson.databind.ObjectMapper

/**
 * Builds the read-only RPC every capture uses, paced by the limiter of its endpoint, optionally backed by a transaction
 * cache, and recording its requests and cache hits as API usage. [maxAttempts] bounds the requests sent for one call.
 */
object CaptureRpcs {
    fun create(
        settings: SolanaRpcSettings,
        mapper: ObjectMapper,
        clock: Clock,
        transactionCache: SharedTransactionCache? = null,
        limiters: RpcLimiterRegistry = RpcLimiterRegistry.shared,
        recorder: ApiUsageRecorder = ApiUsageRecorder.NONE,
        maxAttempts: Int = HttpSolanaReadRpc.DEFAULT_MAX_ATTEMPTS,
    ): SolanaReadRpc {
        val http = HttpSolanaReadRpc(settings, mapper, clock, limiters.timing, limiters.limiterFor(settings), recorder, maxAttempts)
        return if (transactionCache == null) http else CachingSolanaReadRpc(http, transactionCache, recorder)
    }
}
