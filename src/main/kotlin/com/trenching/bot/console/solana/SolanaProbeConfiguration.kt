package com.trenching.bot.console.solana

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.chaincapture.RpcEndpointSource
import com.trenching.bot.console.config.SolanaRpcProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SolanaRpcProperties::class)
class SolanaProbeConfiguration {
    /** Checks whichever RPC account [endpoints] names as active; [SolanaProbe.host] falls back to the primary's host while no account has credits. */
    @Bean
    fun solanaProbe(properties: SolanaRpcProperties, objectMapper: ObjectMapper, recorder: ApiUsageRecorder, endpoints: RpcEndpointSource): SolanaProbe =
        JsonRpcSolanaProbe(
            SolanaRestClients.create(properties.connectTimeout, properties.readTimeout),
            objectMapper,
            endpoints,
            recorder,
            SolanaRpcEndpoint.from(properties.rpcUrl)?.host,
        )
}
