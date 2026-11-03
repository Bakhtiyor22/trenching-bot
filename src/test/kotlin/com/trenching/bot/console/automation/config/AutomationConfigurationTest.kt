package com.trenching.bot.console.automation.config

import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

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
