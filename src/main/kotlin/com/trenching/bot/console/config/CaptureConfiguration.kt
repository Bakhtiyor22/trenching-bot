package com.trenching.bot.console.config

import com.trenching.bot.chaincapture.SharedTransactionCache
import com.trenching.bot.console.capture.*
import com.trenching.bot.console.mapper.CaptureResponseMapper
import com.trenching.bot.marketdata.*
import com.trenching.bot.replay.*
import com.trenching.bot.walletactivity.WalletHistoryCaptureProperties
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Executors
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import tools.jackson.databind.ObjectMapper

@ConfigurationProperties("trenching.console.capture")
class CaptureProperties(val rootDirectory: String = "build/captures") {
    fun validatedRoot(): Path {
        require(rootDirectory.isNotBlank()) { "Capture root directory must not be blank" }
        return Path.of(rootDirectory).toAbsolutePath().normalize()
    }
}

@Configuration(proxyBeanMethods=false)
@Profile("local & !prod")
@ConditionalOnConsoleEnabled
@EnableConfigurationProperties(CaptureProperties::class)
class CaptureConfiguration {
    private companion object {
        const val TRANSACTION_CACHE_DIRECTORY = "transaction-cache"
    }


    @Bean fun captureWorkflow(properties: SolanaRpcProperties, wallet: WalletHistoryCaptureProperties, market: MarketHistoryCaptureProperties, capture: CaptureProperties, rateSource: SolUsdtRateSource, mapper: ObjectMapper, clock: Clock): CaptureWorkflow =
        CaptureWorkflows.create(wallet.withEndpoint(properties.rpcUrl),market.withEndpoint(properties.rpcUrl),rateSource,mapper,clock,SharedTransactionCache(capture.validatedRoot().resolve(TRANSACTION_CACHE_DIRECTORY),mapper))
    @Bean(destroyMethod="close") fun captureFileStore(properties: CaptureProperties, mapper: ObjectMapper): CaptureFileStore = CaptureFileStore(CapturePathPolicy(properties.validatedRoot()),mapper).also { it.initialize() }
    @Bean(destroyMethod="close") fun captureOperationService(store: CaptureFileStore, workflow: CaptureWorkflow, mapper: CaptureResponseMapper, json: ObjectMapper, clock: Clock, rpc: SolanaRpcProperties, rates: ReferenceRateProperties): CaptureOperationService =
        CaptureOperationService(store,workflow,mapper,json,clock,Executors.newSingleThreadExecutor { task -> Thread(task,"capture-console-worker").apply { isDaemon=true } },!rpc.rpcUrl.isNullOrBlank(),rates.enabled,ExecutorCaptureRetryScheduler()).also { it.recover() }
    @Bean(destroyMethod="close") fun captureCaseService(store: CaptureFileStore, workflow: CaptureWorkflow, mapper: CaptureResponseMapper, datasets: com.trenching.bot.console.mapper.DatasetResponseMapper, catalog: ReplayDatasetCatalog, clock: Clock): CaptureCaseService =
        CaptureCaseService(store,workflow,mapper,datasets,catalog,clock,Executors.newSingleThreadExecutor { task -> Thread(task,"capture-case-worker").apply { isDaemon=true } }).also { it.recover() }
    @Bean fun captureSessionService(store: CaptureFileStore, operations: CaptureOperationService, cases: CaptureCaseService, clock: Clock): CaptureSessionService = CaptureSessionService(store,operations,clock,CaptureIds { UUID.randomUUID().toString() },cases)
    @Bean fun captureCatalogService(store: CaptureFileStore, operations: CaptureOperationService, catalog: ReplayDatasetCatalog): CaptureCatalogService = CaptureCatalogService(store,operations,catalog)
}
