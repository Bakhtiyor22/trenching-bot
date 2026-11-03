package com.trenching.bot.console.automation.config

import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.application.AutomationManualRun
import com.trenching.bot.console.automation.application.AutomationRuntime
import com.trenching.bot.console.automation.domain.AutomationDisabledException
import java.time.Clock
import java.util.concurrent.ScheduledExecutorService
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

class AutomationConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(AutomationConsoleConfiguration::class.java, AutomationConfiguration::class.java)

    @Test
    fun `with the local profile, the console and automation enabled, the properties and the scheduler load`() {
        runner.withPropertyValues(LOCAL, CONSOLE, "trenching.automation.enabled=true", "trenching.automation.wallet=$WALLET").run { context ->
            assertThat(context).hasSingleBean(AutomationProperties::class.java).hasSingleBean(AutomationConfiguration::class.java)
        }
    }

    @Test
    fun `with automation disabled the properties still load for the read API, but no scheduler`() {
        runner.withPropertyValues(LOCAL, CONSOLE).run { context ->
            assertThat(context).hasSingleBean(AutomationProperties::class.java).doesNotHaveBean(AutomationConfiguration::class.java)
        }
    }

    @Test
    fun `outside the local profile or without the console nothing loads, even when enabled`() {
        listOf(arrayOf(CONSOLE, "spring.profiles.active=prod"), arrayOf(LOCAL)).forEach { values ->
            runner.withPropertyValues(*values, "trenching.automation.enabled=true", "trenching.automation.wallet=$WALLET").run { context ->
                assertThat(context).doesNotHaveBean(AutomationProperties::class.java).doesNotHaveBean(AutomationConfiguration::class.java)
            }
        }
    }

    @Test
    fun `run now loads with the console alone and refuses while automation is off, since discovery and the scheduler are absent`() {
        runner.withPropertyValues(LOCAL, CONSOLE).withBean(AutomationRuntime::class.java)
            .withBean(SimulationClock.NAME, Clock::class.java, { Clock.systemUTC() })
            .withBean(AutomationManualRun::class.java).run { context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(TaskScheduler::class.java)
                assertFailsWith<AutomationDisabledException> { context.getBean(AutomationManualRun::class.java).request() }
            }
    }

    @Test
    fun `scheduled automation and run now share the one task scheduler, which application yml pins to a single thread`() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration::class.java))
            .withUserConfiguration(AutomationConsoleConfiguration::class.java, AutomationConfiguration::class.java)
            .withPropertyValues(LOCAL, CONSOLE, "trenching.automation.enabled=true", "trenching.automation.wallet=$WALLET")
            .run { context ->
                assertThat(context).hasNotFailed().hasSingleBean(TaskScheduler::class.java).doesNotHaveBean(ScheduledExecutorService::class.java)
                val scheduler = context.getBean(TaskScheduler::class.java) as ThreadPoolTaskScheduler
                assertEquals("1", context.environment.getProperty("spring.task.scheduling.pool.size"), "application.yml pins it, not Boot's default")
                assertEquals(1, scheduler.scheduledThreadPoolExecutor.corePoolSize, "discovery, the pipeline, the sweep, the session tick and run now never overlap")
            }
    }

    @Test
    fun `enabling automation without a wallet fails startup`() {
        runner.withPropertyValues(LOCAL, CONSOLE, "trenching.automation.enabled=true").run { context ->
            assertThat(context).hasFailed()
            assertTrue(generateSequence(context.startupFailure) { it.cause }.any { it.message.orEmpty().contains("trenching.automation.wallet") })
        }
    }

    private companion object {
        const val LOCAL = "spring.profiles.active=local"
        const val CONSOLE = "trenching.console.enabled=true"
        const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
    }
}
