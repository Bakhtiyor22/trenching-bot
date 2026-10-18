package com.trenching.bot.walletactivity

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.chaincapture.CaptureRpcs
import com.trenching.bot.chaincapture.RpcLimiterRegistry
import com.trenching.bot.chaincapture.SharedTransactionCache
import com.trenching.bot.chaincapture.SolanaRpcSettings
import com.trenching.bot.walletactivity.application.RecentWalletActivityService
import java.time.Clock
import tools.jackson.databind.ObjectMapper

object WalletActivityLookups {
    /**
     * A lookup paced with every capture on the same endpoint, sharing their [transactionCache] and counting its requests in
     * [recorder]. Each read is sent once: a page's budget has no room for a retry, and a rate limit then ends the page with its
     * `Retry-After`.
     */
    fun create(
        settings: SolanaRpcSettings,
        mapper: ObjectMapper,
        clock: Clock,
        transactionCache: SharedTransactionCache?,
        recorder: ApiUsageRecorder,
        limiters: RpcLimiterRegistry = RpcLimiterRegistry.shared,
    ): WalletActivityLookup {
        settings.validate()
        val rpc = CaptureRpcs.create(settings, mapper, clock, transactionCache, limiters, recorder, WalletActivityLimits.PROVIDER_ATTEMPTS)
        return RecentWalletActivityService(rpc, settings.maxConcurrentRequests)
    }
}
