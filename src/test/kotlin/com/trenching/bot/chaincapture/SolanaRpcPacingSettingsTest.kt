package com.trenching.bot.chaincapture

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.marketdata.MarketHistoryCaptureProperties
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import com.trenching.bot.walletactivity.WalletHistoryCaptureProperties
import java.time.Clock
import java.time.Duration
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.ObjectMapper

class SolanaRpcPacingSettingsTest {
    @Test
    fun `both capture properties default to four requests in flight and one start every 125 ms`() {
        listOf<SolanaRpcSettings>(MarketHistoryCaptureProperties(), WalletHistoryCaptureProperties()).forEach {
            assertEquals(4, it.maxConcurrentRequests)
            assertEquals(Duration.ofMillis(125), it.minRequestInterval)
            it.validate()
        }
    }

    @Test
    fun `pacing outside its bounds is rejected and the old fixed pacing stays allowed`() {
        TestSettings(maxConcurrentRequests = 1, minRequestInterval = Duration.ofMillis(500)).validate()
        TestSettings(maxConcurrentRequests = 16, minRequestInterval = Duration.ofMillis(20)).validate()
        listOf(
            TestSettings(maxConcurrentRequests = 0),
            TestSettings(maxConcurrentRequests = 17),
            TestSettings(minRequestInterval = Duration.ofMillis(19)),
            TestSettings(minRequestInterval = Duration.ZERO),
            TestSettings(minRequestInterval = Duration.ofSeconds(11)),
        ).forEach { settings ->
            assertEquals("PACING", assertFailsWith<CaptureValidationException> { settings.validate() }.category, settings.toString())
        }
    }

    @Test
    fun `application yml documents the defaults under both capture prefixes`() {
        val yaml = YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")) }.`object`!!
        listOf("marketdata.capture", "walletactivity.capture").forEach { prefix ->
            assertEquals("4", yaml.getProperty("$prefix.max-concurrent-requests"))
            assertEquals("125ms", yaml.getProperty("$prefix.min-request-interval"))
        }
    }

    @Test
    fun `invalid pacing in configuration fails startup for both captures`() {
        val mapper = Supplier<ObjectMapper> { tools.jackson.databind.json.JsonMapper.builder().build() }
        val clock = Supplier<Clock> { Clock.systemUTC() }
        listOf(
            MarketHistoryCaptureConfiguration::class.java to "marketdata.capture.max-concurrent-requests=17",
            WalletHistoryCaptureConfiguration::class.java to "walletactivity.capture.min-request-interval=5ms",
        ).forEach { (configuration, property) ->
            ApplicationContextRunner().withUserConfiguration(configuration)
                .withBean(ObjectMapper::class.java, mapper)
                .withBean(SimulationClock.NAME, Clock::class.java, clock)
                .withBean(ApiUsageRecorder::class.java, Supplier { ApiUsageRecorder.NONE })
                .withPropertyValues(property)
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.startupFailure).hasRootCauseInstanceOf(CaptureValidationException::class.java)
                }
        }
    }
}
