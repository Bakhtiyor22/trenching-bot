package com.trenching.bot.console

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.application.WalletActivityConsoleService
import com.trenching.bot.console.config.SolanaRpcProperties
import com.trenching.bot.console.config.WalletActivityConfiguration
import com.trenching.bot.console.domain.WalletActivityUnavailableException
import com.trenching.bot.walletactivity.WalletActivityLookup
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import com.trenching.bot.walletactivity.WalletHistoryCaptureProperties
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

class WalletActivityConfigurationTest {
    private val context = ApplicationContextRunner()
        .withUserConfiguration(WalletActivityConfiguration::class.java)
        .withBean(ObjectMapper::class.java, Supplier { JsonMapper.builder().build() })
        .withBean(ApiUsageRecorder::class.java, Supplier { ApiUsageRecorder.NONE })
        .withBean(Clock::class.java, Supplier { Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC) })
        .withBean(SolanaRpcProperties::class.java, Supplier { SolanaRpcProperties() })
        .withBean(WalletHistoryCaptureProperties::class.java, Supplier { WalletHistoryCaptureProperties() })

    @Test
    fun `the console without the capture console composes the lookup and service, and an unset RPC answers RPC_NOT_CONFIGURED without a request`() {
        context.withPropertyValues("trenching.console.enabled=true").run { result ->
            assertThat(result).hasNotFailed().hasSingleBean(WalletActivityLookup::class.java).hasSingleBean(WalletActivityConsoleService::class.java)
            val failure = assertFailsWith<WalletActivityUnavailableException> {
                result.getBean(WalletActivityConsoleService::class.java).activity(DECU.value, null, null, refresh = false)
            }
            assertEquals(WalletActivityUnavailable.RPC_NOT_CONFIGURED, failure.reason)
        }
    }

    @Test
    fun `nothing is registered while the console is disabled`() {
        context.run { result ->
            assertThat(result).hasNotFailed().doesNotHaveBean(WalletActivityLookup::class.java).doesNotHaveBean(WalletActivityConsoleService::class.java)
        }
    }

    private companion object {
        val DECU: WalletAddress = WalletAddress.of("4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9")
    }
}
