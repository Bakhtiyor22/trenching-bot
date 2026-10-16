package com.trenching.bot.replay

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.chaincapture.SharedTransactionCache
import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.marketdata.MarketHistoryCaptureProperties
import com.trenching.bot.marketdata.ReferenceRateCaptures
import com.trenching.bot.marketdata.SolUsdtRateSource
import com.trenching.bot.replay.application.CaptureWorkflowService
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import com.trenching.bot.walletactivity.WalletHistoryCaptureProperties
import java.time.Clock
import tools.jackson.databind.ObjectMapper

object CaptureWorkflows {
    /**
     * [ratesEnabled] is whether [rateSource] is configured; only then may a market capture run in decided mode. [recorder]
     * counts the RPC requests; [rateSource] counts its own.
     */
    fun create(
        walletProperties: WalletHistoryCaptureProperties,
        marketProperties: MarketHistoryCaptureProperties,
        rateSource: SolUsdtRateSource,
        ratesEnabled: Boolean,
        mapper: ObjectMapper,
        clock: Clock,
        transactionCache: SharedTransactionCache? = null,
        recorder: ApiUsageRecorder = ApiUsageRecorder.NONE,
    ): CaptureWorkflow {
        val wallets = WalletHistoryCaptureConfiguration()
        val markets = MarketHistoryCaptureConfiguration()
        val recordedWallets = wallets.recordedWalletHistory(mapper)
        val recordedMarkets = markets.recordedMarketHistory(mapper)
        val recordedRates = ReferenceRateCaptures.recorded(recordedMarkets, mapper, clock)
        return CaptureWorkflowService(recordedWallets, recordedMarkets, recordedRates,
            { observer -> wallets.observedCapture(walletProperties, mapper, clock, observer, transactionCache, recorder) },
            { observer -> markets.observedCapture(marketProperties, mapper, clock, observer, transactionCache, recorder) },
            { observer -> ReferenceRateCaptures.observedCapture(rateSource, recordedMarkets, mapper, clock, observer) }, mapper,
            rateSource.takeIf { ratesEnabled })
    }
}
