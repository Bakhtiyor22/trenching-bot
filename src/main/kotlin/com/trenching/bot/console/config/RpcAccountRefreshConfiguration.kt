package com.trenching.bot.console.config

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.solana.SolanaRestClients
import com.trenching.bot.console.solana.accounts.application.RpcAccountBalances
import com.trenching.bot.console.solana.accounts.application.RpcAccountDirectory
import com.trenching.bot.console.solana.accounts.application.RpcAccountHealth
import com.trenching.bot.console.solana.accounts.application.RpcAccountRefresh
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfigs
import com.trenching.bot.console.solana.accounts.infrastructure.helius.HeliusRestClients
import com.trenching.bot.console.solana.accounts.infrastructure.helius.HeliusUsageClient
import com.trenching.bot.console.solana.accounts.infrastructure.rpc.JsonRpcAccountHealth
import com.trenching.bot.console.solana.accounts.infrastructure.schedule.RpcAccountRefreshScheduler
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling
import tools.jackson.databind.ObjectMapper

/**
 * The timed balance refresh of the RPC accounts. It calls the provider's admin API, so like the capture console it exists only with the
 * console enabled under the `local` or `prod` profile: a test JVM or a second process with the variables set never reaches the provider.
 * It turns scheduling on itself, because the automation's `@EnableScheduling` exists only when the automation does; a single application
 * instance is assumed.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local | prod")
@ConditionalOnConsoleEnabled
@EnableScheduling
class RpcAccountRefreshConfiguration {
    @Bean
    fun rpcAccountBalances(configs: RpcAccountConfigs, properties: SolanaRpcProperties, mapper: ObjectMapper, recorder: ApiUsageRecorder): RpcAccountBalances =
        HeliusUsageClient(configs, properties.usage.baseUrl, HeliusRestClients.create(), mapper, recorder)

    @Bean
    fun rpcAccountHealth(configs: RpcAccountConfigs, properties: SolanaRpcProperties, mapper: ObjectMapper, recorder: ApiUsageRecorder): RpcAccountHealth =
        JsonRpcAccountHealth(configs, SolanaRestClients.create(properties.connectTimeout, properties.readTimeout), mapper, recorder)

    @Bean
    fun rpcAccountRefresh(
        configs: RpcAccountConfigs,
        directory: RpcAccountDirectory,
        balances: RpcAccountBalances,
        health: RpcAccountHealth,
        @Qualifier(SimulationClock.NAME) clock: Clock,
    ): RpcAccountRefresh = RpcAccountRefresh(configs, directory, balances, health, clock)

    @Bean
    fun rpcAccountRefreshScheduler(refresh: RpcAccountRefresh): RpcAccountRefreshScheduler = RpcAccountRefreshScheduler(refresh)
}
