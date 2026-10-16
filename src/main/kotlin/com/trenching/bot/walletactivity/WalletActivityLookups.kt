package com.trenching.bot.walletactivity

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.chaincapture.CaptureRpcs
import com.trenching.bot.chaincapture.SharedTransactionCache
import com.trenching.bot.walletactivity.application.RecentWalletActivityService
import java.time.Clock
import tools.jackson.databind.ObjectMapper

object WalletActivityLookups {
    /** Reads through the endpoint's shared limiter and [transactionCache], so captures and lookups never fetch a transaction twice. */
    fun create(
        properties: WalletHistoryCaptureProperties,
        mapper: ObjectMapper,
        clock: Clock,
        transactionCache: SharedTransactionCache?,
        recorder: ApiUsageRecorder,
    ): WalletActivityLookup {
        properties.validate()
        return RecentWalletActivityService(!properties.endpoint.isNullOrBlank()) {
            CaptureRpcs.create(properties, mapper, clock, transactionCache, recorder = recorder)
        }
    }
}
