package com.trenching.bot.marketdata

import com.trenching.bot.config.SimulationClock
import com.trenching.bot.marketdata.infrastructure.rates.BinanceSolUsdtRateSource
import com.trenching.bot.marketdata.infrastructure.rates.DisabledSolUsdtRateSource
import java.net.http.HttpClient
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReferenceRateProperties::class)
class ReferenceRateConfiguration {
    @Bean
    fun solUsdtRateSource(
        properties: ReferenceRateProperties,
        objectMapper: ObjectMapper,
        @Qualifier(SimulationClock.NAME) clock: Clock,
    ): SolUsdtRateSource {
        if (!properties.enabled) return DisabledSolUsdtRateSource()
        val httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(properties.readTimeout) }
        return BinanceSolUsdtRateSource(RestClient.builder().requestFactory(requestFactory).build(), properties, objectMapper, clock)
    }
}
