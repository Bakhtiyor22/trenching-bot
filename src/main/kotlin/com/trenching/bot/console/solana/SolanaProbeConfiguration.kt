package com.trenching.bot.console.solana

import com.trenching.bot.console.config.SolanaRpcProperties
import java.net.http.HttpClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SolanaRpcProperties::class)
class SolanaProbeConfiguration {
    @Bean
    fun solanaProbe(properties: SolanaRpcProperties, objectMapper: ObjectMapper): SolanaProbe =
        JsonRpcSolanaProbe(SolanaRpcEndpoint.from(properties.rpcUrl), restClient(properties), objectMapper)

    private fun restClient(properties: SolanaRpcProperties): RestClient {
        val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(properties.readTimeout) }
        return RestClient.builder().requestFactory(requestFactory).build()
    }
}
