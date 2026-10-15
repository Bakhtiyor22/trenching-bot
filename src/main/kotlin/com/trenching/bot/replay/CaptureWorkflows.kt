package com.trenching.bot.replay

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
    fun create(
        walletProperties: WalletHistoryCaptureProperties,
        marketProperties: MarketHistoryCaptureProperties,
        rateSource: SolUsdtRateSource,
        mapper: ObjectMapper,
        clock: Clock,
        transactionCache: SharedTransactionCache? = null,
    ): CaptureWorkflow {
        val wallets = WalletHistoryCaptureConfiguration()
        val markets = MarketHistoryCaptureConfiguration()
        val recordedWallets = wallets.recordedWalletHistory(mapper)
        val recordedMarkets = markets.recordedMarketHistory(mapper)
        val recordedRates = ReferenceRateCaptures.recorded(recordedMarkets, mapper, clock)
        return CaptureWorkflowService(recordedWallets, recordedMarkets, recordedRates,
            { observer -> wallets.observedCapture(walletProperties, mapper, clock, observer, transactionCache) },
            { observer -> markets.observedCapture(marketProperties, mapper, clock, observer, transactionCache) },
            { observer -> ReferenceRateCaptures.observedCapture(rateSource, recordedMarkets, mapper, clock, observer) }, mapper)
    }
}
