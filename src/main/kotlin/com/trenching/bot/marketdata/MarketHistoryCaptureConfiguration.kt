package com.trenching.bot.marketdata

import com.trenching.bot.chaincapture.CaptureCheckpointObserver
import com.trenching.bot.chaincapture.CaptureRpcs
import com.trenching.bot.chaincapture.SharedTransactionCache
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.marketdata.application.MarketHistoryCaptureService
import com.trenching.bot.marketdata.application.RecordedMarketHistoryService
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import com.trenching.bot.marketdata.infrastructure.extraction.PumpFunTradeObservationExtractor
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MarketHistoryCaptureProperties::class)
class MarketHistoryCaptureConfiguration {
    @Bean
    fun marketHistoryCapture(
        properties: MarketHistoryCaptureProperties,
        objectMapper: ObjectMapper,
        @Qualifier(SimulationClock.NAME) clock: Clock,
    ): MarketHistoryCapture = observedCapture(properties, objectMapper, clock, CaptureCheckpointObserver.NONE)

    fun cachedCapture(
        properties: MarketHistoryCaptureProperties,
        objectMapper: ObjectMapper,
        clock: Clock,
        transactionCache: SharedTransactionCache?,
    ): MarketHistoryCapture = observedCapture(properties, objectMapper, clock, CaptureCheckpointObserver.NONE, transactionCache)

    fun observedCapture(
        properties: MarketHistoryCaptureProperties,
        objectMapper: ObjectMapper,
        clock: Clock,
        observer: CaptureCheckpointObserver,
        transactionCache: SharedTransactionCache? = null,
    ): MarketHistoryCapture {
        properties.validate()
        return MarketHistoryCaptureService(CaptureRpcs.create(properties, objectMapper, clock, transactionCache), MarketHistoryArtifactStore(objectMapper), clock, observer)
    }

    @Bean
    fun recordedMarketHistory(objectMapper: ObjectMapper): RecordedMarketHistory =
        RecordedMarketHistoryService(MarketHistoryArtifactStore(objectMapper), PumpFunTradeObservationExtractor())
}
