package com.trenching.bot.apiusage

import com.trenching.bot.apiusage.application.ApiUsageAccumulator
import com.trenching.bot.apiusage.application.ApiUsageBucketStore
import com.trenching.bot.apiusage.application.ApiUsageFlusher
import com.trenching.bot.apiusage.application.ApiUsageReportService
import com.trenching.bot.apiusage.infrastructure.persistence.ApiUsageBucketRepository
import com.trenching.bot.apiusage.mapper.ApiUsageMapper
import com.trenching.bot.config.SimulationClock
import java.time.Clock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The recorder exists whenever the application runs, so every provider adapter can count its requests. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiUsageProperties::class)
class ApiUsageConfiguration {
    @Bean
    fun apiUsageAccumulator(@Qualifier(SimulationClock.NAME) clock: Clock): ApiUsageAccumulator = ApiUsageAccumulator(clock)

    @Bean(destroyMethod = "close")
    fun apiUsageFlusher(accumulator: ApiUsageAccumulator, store: ApiUsageBucketStore, properties: ApiUsageProperties): ApiUsageFlusher =
        ApiUsageFlusher(accumulator, store, properties.flushInterval).also { it.start() }

    @Bean
    fun apiUsageReports(
        repository: ApiUsageBucketRepository,
        accumulator: ApiUsageAccumulator,
        mapper: ApiUsageMapper,
        pacing: ObjectProvider<ApiPacingSettings>,
        @Qualifier(SimulationClock.NAME) clock: Clock,
    ): ApiUsageReports = ApiUsageReportService(repository, accumulator, mapper, pacing.getIfAvailable { ApiPacingSettings.NONE }, clock)
}
