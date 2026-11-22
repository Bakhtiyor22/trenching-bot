package com.trenching.bot.console.config

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.solana.accounts.InMemoryRpcAccountStore
import com.trenching.bot.console.solana.accounts.RpcAccountTestData
import com.trenching.bot.console.solana.accounts.application.RpcAccountDirectory
import com.trenching.bot.console.solana.accounts.application.RpcAccountRefresh
import com.trenching.bot.console.solana.accounts.application.RpcAccountStore
import com.trenching.bot.console.solana.accounts.infrastructure.schedule.RpcAccountRefreshScheduler
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import tools.jackson.databind.json.JsonMapper

/**
 * The balance refresh calls the provider's admin API, so it exists only with the console enabled under the `local` or `prod` profile (R9).
 * The directory, which the probe and every capture read, exists whatever the profile.
 */
class RpcAccountRefreshConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(RpcAccountsConfiguration::class.java, RpcAccountRefreshConfiguration::class.java)
        .withBean(SimulationClock.NAME, Clock::class.java, { Clock.systemUTC() })
        .withBean(RpcAccountStore::class.java, { InMemoryRpcAccountStore() })
        .withBean(ApiUsageRecorder::class.java, { ApiUsageRecorder.NONE })
        .withBean(JsonMapper::class.java, { JsonMapper.builder().build() })
        .withPropertyValues(
            "trenching.solana.rpc-url=${RpcAccountTestData.PRIMARY_URL}",
            "trenching.solana.backup-rpc-url=${RpcAccountTestData.BACKUP_URL}",
            "trenching.solana.usage.primary-project-id=${RpcAccountTestData.PRIMARY_PROJECT}",
        )

    @Test
    fun `with the console enabled under local the refresh and its schedule exist`() {
        runner.withPropertyValues("spring.profiles.active=local", "trenching.console.enabled=true").run { context ->
            assertNotNull(context.getBean(RpcAccountRefresh::class.java))
            assertNotNull(context.getBean(RpcAccountRefreshScheduler::class.java))
            assertEquals(1, context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java).size)
        }
    }

    @Test
    fun `with the console enabled under prod the refresh exists too`() {
        runner.withPropertyValues("spring.profiles.active=prod", "trenching.console.enabled=true").run { context ->
            assertEquals(1, context.getBeansOfType(RpcAccountRefresh::class.java).size)
        }
    }

    @Test
    fun `under the test profile nothing refreshes and nothing is scheduled, though the directory exists`() {
        runner.withPropertyValues("spring.profiles.active=test", "trenching.console.enabled=true").run { context ->
            assertEquals(0, context.getBeansOfType(RpcAccountRefresh::class.java).size)
            assertEquals(0, context.getBeansOfType(RpcAccountRefreshScheduler::class.java).size)
            assertEquals(0, context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java).size)
            assertEquals(1, context.getBeansOfType(RpcAccountDirectory::class.java).size)
        }
    }

    @Test
    fun `with the console disabled even local does not refresh`() {
        runner.withPropertyValues("spring.profiles.active=local").run { context ->
            assertEquals(0, context.getBeansOfType(RpcAccountRefresh::class.java).size)
            assertEquals(1, context.getBeansOfType(RpcAccountDirectory::class.java).size)
        }
    }
}
