package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.config.SimulationClock
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

class WalletHistoryCaptureConfigurationTest {
    private val context = ApplicationContextRunner()
        .withUserConfiguration(WalletHistoryCaptureConfiguration::class.java)
        .withBean(ObjectMapper::class.java, Supplier { JsonMapper.builder().build() })
        .withBean(ApiUsageRecorder::class.java, Supplier { ApiUsageRecorder.NONE })
        .withBean(SimulationClock.NAME, Clock::class.java, Supplier { Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC) })

    @Test
    fun `optional unconfigured capture composes both public ports without provider calls or JPA`() {
        context.run { result ->
            assertThat(result).hasNotFailed().hasSingleBean(WalletHistoryCapture::class.java).hasSingleBean(RecordedWalletHistory::class.java)
        }
    }

    @Test
    fun `invalid configuration fails through explicit bounded timeout validation`() {
        context.withPropertyValues("walletactivity.capture.read-timeout=0s").run { result ->
            assertThat(result).hasFailed()
            assertThat(result.startupFailure).hasRootCauseInstanceOf(CaptureValidationException::class.java)
        }
    }
}
