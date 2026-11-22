package com.trenching.bot.console.config

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.solana.SolanaRestClients
import com.trenching.bot.console.solana.SolanaRpcEndpoint
import com.trenching.bot.console.solana.accounts.application.RpcAccountDirectory
import com.trenching.bot.console.solana.accounts.application.RpcAccountNetworkCheck
import com.trenching.bot.console.solana.accounts.application.RpcAccountStore
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfig
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfigs
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import com.trenching.bot.console.solana.accounts.infrastructure.rpc.JsonRpcAccountNetworkCheck
import java.time.Clock
import java.time.ZoneId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * The primary and backup RPC accounts: their shared state and the check of the backup's network. The directory exists wherever the
 * application does, since the probe and every capture read it; the balance reads and their timed refresh are in [RpcAccountRefreshConfiguration].
 * With no RPC URL configured nothing is read and nothing is sent.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SolanaRpcProperties::class)
class RpcAccountsConfiguration {
    @Bean
    fun rpcAccountConfigs(properties: SolanaRpcProperties): RpcAccountConfigs = RpcAccountConfigs(
        listOfNotNull(
            account(RpcAccountName.PRIMARY, properties.rpcUrl, properties.usage.primaryProjectId),
            account(RpcAccountName.BACKUP, properties.backupRpcUrl, properties.usage.backupProjectId),
        ),
    )

    @Bean
    fun rpcAccountNetworkCheck(configs: RpcAccountConfigs, properties: SolanaRpcProperties, mapper: ObjectMapper, recorder: ApiUsageRecorder): RpcAccountNetworkCheck =
        JsonRpcAccountNetworkCheck(configs, SolanaRestClients.create(properties.connectTimeout, properties.readTimeout), mapper, recorder)

    /** [zone] is the automation's own, so every message reads times the same way; the accounts do not depend on the automation's classes. */
    @Bean
    fun rpcAccountDirectory(
        configs: RpcAccountConfigs,
        store: RpcAccountStore,
        networkCheck: RpcAccountNetworkCheck,
        properties: SolanaRpcProperties,
        @Qualifier(SimulationClock.NAME) clock: Clock,
        @Value("\${trenching.automation.zone:+05:00}") zone: ZoneId,
    ): RpcAccountDirectory =
        RpcAccountDirectory(configs, store.load(), store, networkCheck, clock, properties.usage.lowBalancePercent, properties.usage.resumeCredits, zone)

    private fun account(name: RpcAccountName, rpcUrl: String?, projectId: String?): RpcAccountConfig? {
        val endpoint = rpcUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val host = requireNotNull(SolanaRpcEndpoint.from(endpoint)) { "A configured RPC account has an endpoint account=${name.label}" }.host
        return RpcAccountConfig(name, endpoint, host, projectId?.trim()?.takeIf { it.isNotEmpty() })
    }
}
