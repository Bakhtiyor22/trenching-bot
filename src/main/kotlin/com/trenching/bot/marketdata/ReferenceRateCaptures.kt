package com.trenching.bot.marketdata

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.marketdata.application.ReferenceRateCaptureService
import com.trenching.bot.marketdata.infrastructure.capture.ReferenceRateArtifactStore
import com.trenching.bot.marketdata.infrastructure.rates.DisabledSolUsdtRateSource
import java.time.Clock
import tools.jackson.databind.ObjectMapper

/** Builds the user-run rate capture and its offline reader for the command-line tasks; neither is a Spring bean. */
object ReferenceRateCaptures {
    /** Calls Binance through the display rate source, enabled for this one command with its default timeouts; not counted as API usage. */
    fun capture(markets: RecordedMarketHistory, mapper: ObjectMapper, clock: Clock): ReferenceRateCapture {
        val source = ReferenceRateConfiguration().solUsdtRateSource(ReferenceRateProperties(enabled = true), mapper, clock, ApiUsageRecorder.NONE)
        return ReferenceRateCaptureService(source, markets, ReferenceRateArtifactStore(mapper), clock)
    }

    fun observedCapture(
        source: SolUsdtRateSource,
        markets: RecordedMarketHistory,
        mapper: ObjectMapper,
        clock: Clock,
        observer: com.trenching.bot.chaincapture.CaptureCheckpointObserver,
    ): ReferenceRateCapture = ReferenceRateCaptureService(source, markets, ReferenceRateArtifactStore(mapper), clock, observer)

    /** Offline: verifies saved rates and never calls a source. */
    fun recorded(markets: RecordedMarketHistory, mapper: ObjectMapper, clock: Clock): RecordedReferenceRates =
        ReferenceRateCaptureService(DisabledSolUsdtRateSource(), markets, ReferenceRateArtifactStore(mapper), clock)
}
