package com.trenching.bot.marketdata

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.config.SimulationClock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Supplier
import kotlin.test.assertEquals
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

class MarketHistoryCaptureConfigurationTest {
    private val context = ApplicationContextRunner()
        .withUserConfiguration(MarketHistoryCaptureConfiguration::class.java)
        .withBean(ObjectMapper::class.java, Supplier { JsonMapper.builder().build() })
        .withBean(SimulationClock.NAME, Clock::class.java, Supplier { Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC) })

    @Test
    fun `optional unconfigured capture composes both public ports without provider calls or JPA`() {
        context.run { result ->
            assertThat(result).hasNotFailed().hasSingleBean(MarketHistoryCapture::class.java).hasSingleBean(RecordedMarketHistory::class.java)
        }
    }

    @Test
    fun `defaults match wallet capture and invalid timeouts fail startup`() {
        val defaults = MarketHistoryCaptureProperties()
        assertEquals(null, defaults.endpoint)
        assertEquals(listOf(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofMinutes(10)),
            listOf(defaults.connectTimeout, defaults.readTimeout, defaults.operationTimeout))
        context.withPropertyValues("marketdata.capture.read-timeout=0s").run { result ->
            assertThat(result).hasFailed()
            assertThat(result.startupFailure).hasRootCauseInstanceOf(CaptureValidationException::class.java)
        }
    }
}
