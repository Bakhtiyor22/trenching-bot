package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.HttpSolanaReadRpc
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
    ): WalletHistoryCapture {
        properties.validate()
        return WalletHistoryCaptureService(HttpSolanaReadRpc(properties, objectMapper, clock), objectMapper, clock)
    }

    @Bean
    fun recordedWalletHistory(objectMapper: ObjectMapper): RecordedWalletHistory =
        RecordedWalletHistoryService(WalletHistoryArtifactStore(objectMapper), PumpFunBuyDecoder())
}
