package com.trenching.bot.walletactivity

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.chaincapture.CaptureCheckpointObserver
import com.trenching.bot.chaincapture.CaptureRpcs
import com.trenching.bot.chaincapture.SharedTransactionCache
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.walletactivity.application.RecordedWalletHistoryService
import com.trenching.bot.walletactivity.application.WalletHistoryCaptureService
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoder
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WalletHistoryCaptureProperties::class)
class WalletHistoryCaptureConfiguration {
    @Bean
    fun walletHistoryCapture(
        properties: WalletHistoryCaptureProperties,
        objectMapper: ObjectMapper,
        @Qualifier(SimulationClock.NAME) clock: Clock,
        recorder: ApiUsageRecorder,
    ): WalletHistoryCapture = observedCapture(properties, objectMapper, clock, CaptureCheckpointObserver.NONE, recorder = recorder)

    fun cachedCapture(
        properties: WalletHistoryCaptureProperties,
        objectMapper: ObjectMapper,
        clock: Clock,
        transactionCache: SharedTransactionCache?,
    ): WalletHistoryCapture = observedCapture(properties, objectMapper, clock, CaptureCheckpointObserver.NONE, transactionCache)

    fun observedCapture(
        properties: WalletHistoryCaptureProperties,
        objectMapper: ObjectMapper,
        clock: Clock,
        observer: CaptureCheckpointObserver,
        transactionCache: SharedTransactionCache? = null,
        recorder: ApiUsageRecorder = ApiUsageRecorder.NONE,
    ): WalletHistoryCapture {
        properties.validate()
        return WalletHistoryCaptureService(CaptureRpcs.create(properties, objectMapper, clock, transactionCache, recorder = recorder), objectMapper, clock, properties.maxConcurrentRequests, observer)
    }

    @Bean
    fun recordedWalletHistory(objectMapper: ObjectMapper): RecordedWalletHistory =
        RecordedWalletHistoryService(WalletHistoryArtifactStore(objectMapper), PumpFunBuyDecoder())
}
