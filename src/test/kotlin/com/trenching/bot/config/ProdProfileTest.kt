package com.trenching.bot.config

import com.trenching.bot.console.api.CaptureController
import com.trenching.bot.console.api.CaptureExceptionHandler
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.config.CaptureConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.bootstrap.DefaultBootstrapContext
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.Profiles
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.DefaultResourceLoader

class ProdProfileTest {

    @Test
    fun `prod resolves the Oracle server settings`() {
        val environment = environment(listOf("prod"), DATABASE_VARIABLES)

        assertEquals(listOf("prod"), environment.activeProfiles.toList())

        assertEquals("jdbc:postgresql://127.0.0.1:5432/trenching_bot", environment.getProperty("spring.datasource.url"))
        assertEquals("db-user", environment.getProperty("spring.datasource.username"))
        assertEquals("db-password", environment.getProperty("spring.datasource.password"))
        assertEquals("update", environment.getProperty("spring.jpa.hibernate.ddl-auto"))
        assertEquals("false", environment.getProperty("spring.jpa.show-sql"))
        assertEquals("false", environment.getProperty("spring.jpa.open-in-view"))
        assertEquals("UTC", environment.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone"))
        assertEquals("127.0.0.1", environment.getProperty("server.address"))
        assertEquals("8081", environment.getProperty("server.port"))
        assertEquals("true", environment.getProperty("trenching.console.enabled"))
        assertEquals("/data/captures", environment.getProperty("trenching.console.capture.root-directory"))
        assertEquals("false", environment.getProperty("trenching.automation.enabled"))
        assertEquals("true", environment.getProperty("trenching.automation.capture-enabled"))
        assertEquals("true", environment.getProperty("trenching.automation.poll-always"))
        assertEquals(DECU_WALLET, environment.getProperty("trenching.automation.wallet"))
        assertEquals("100000", environment.getProperty("trenching.automation.max-requests-per-24h"))
        assertEquals("true", environment.getProperty("marketdata.reference-rates.enabled"))
        assertEquals(BINANCE_MIRROR, environment.getProperty("marketdata.reference-rates.base-url"))
        assertEquals("/data", environment.getProperty("management.health.diskspace.path"))
        assertEquals("never", environment.getProperty("management.endpoint.health.show-details"))
        assertEquals("/data/logs/trenching-bot.log", environment.getProperty("logging.file.name"))
        assertEquals("INFO", environment.getProperty("logging.level.root"))
        assertEquals("INFO", environment.getProperty("logging.level.com.trenching.bot"))
        assertSharedPacingAndRolling(environment)
    }

    @Test
    fun `prod has no fallback for any database variable`() {
        val withoutPassword = environment(listOf("prod"), mapOf("DATABASE_URL" to "jdbc:x", "DATABASE_USERNAME" to "db-user"))
        val withoutUrl = environment(listOf("prod"), mapOf("DATABASE_USERNAME" to "db-user", "DATABASE_PASSWORD" to "db-password"))

        assertFailsWith<IllegalArgumentException> { withoutPassword.getProperty("spring.datasource.password") }
        assertFailsWith<IllegalArgumentException> { withoutUrl.getProperty("spring.datasource.url") }
    }

    @Test
    fun `an operating system variable switches automation on under prod`() {
        val environment = environment(listOf("prod"), DATABASE_VARIABLES + mapOf("TRENCHING_AUTOMATION_ENABLED" to "true"))

        assertEquals(listOf("prod"), environment.activeProfiles.toList())

        assertEquals("true", environment.getProperty("trenching.automation.enabled"))
    }

    @Test
    fun `local alone leaves automation off and the server on every interface, and inherits the shared pacing`() {
        val environment = environment(listOf("local"), emptyMap())

        assertEquals(listOf("local"), environment.activeProfiles.toList())

        assertEquals("false", environment.getProperty("trenching.automation.enabled"))
        assertEquals("false", environment.getProperty("trenching.automation.capture-enabled"))
        assertEquals("false", environment.getProperty("trenching.automation.poll-always"))
        assertNull(environment.getProperty("server.address"))
        assertEquals("true", environment.getProperty("spring.jpa.show-sql"))
        assertEquals("update", environment.getProperty("spring.jpa.hibernate.ddl-auto"))
        assertEquals("true", environment.getProperty("marketdata.reference-rates.enabled"))
        assertEquals("always", environment.getProperty("management.endpoint.health.show-details"))
        assertEquals("DEBUG", environment.getProperty("logging.level.com.trenching.bot"))
        assertSharedPacingAndRolling(environment)
    }

    @Test
    fun `the test profile gets the shared pacing but never live reference rates, the console or automation`() {
        val environment = environment(listOf("test"), emptyMap())

        assertSharedPacingAndRolling(environment)
        assertEquals(BINANCE_MIRROR, environment.getProperty("marketdata.reference-rates.base-url"))
        assertEquals(listOf("test"), environment.activeProfiles.toList())
        assertNull(environment.getProperty("marketdata.reference-rates.enabled"))
        assertNull(environment.getProperty("trenching.console.enabled"))
        assertEquals("false", environment.getProperty("trenching.automation.enabled"))
    }

    @Test
    fun `the capture and automation gates open under local and prod and stay shut under test`() {
        listOf(
            CaptureConfiguration::class.java,
            CaptureController::class.java,
            CaptureExceptionHandler::class.java,
            ConditionalOnAutomationConsole::class.java,
        ).forEach { gate ->
            val expression = requireNotNull(AnnotatedElementUtils.findMergedAnnotation(gate, Profile::class.java)) { "${gate.simpleName} has no @Profile" }
            val profiles = Profiles.of(*expression.value)

            assertTrue(accepts(profiles, "local"), gate.simpleName)
            assertTrue(accepts(profiles, "prod"), gate.simpleName)
            assertFalse(accepts(profiles, "test"), gate.simpleName)
            assertFalse(accepts(profiles), gate.simpleName)
        }
    }

    private fun accepts(profiles: Profiles, vararg active: String): Boolean {
        val environment = StandardEnvironment()
        environment.setActiveProfiles(*active)
        return environment.acceptsProfiles(profiles)
    }

    private fun assertSharedPacingAndRolling(environment: StandardEnvironment) {
        listOf("marketdata.capture", "walletactivity.capture").forEach { prefix ->
            assertEquals("2", environment.getProperty("$prefix.max-concurrent-requests"))
            assertEquals("250ms", environment.getProperty("$prefix.min-request-interval"))
        }
        assertEquals("10MB", environment.getProperty("logging.logback.rollingpolicy.max-file-size"))
        assertEquals("7", environment.getProperty("logging.logback.rollingpolicy.max-history"))
        assertEquals("500MB", environment.getProperty("logging.logback.rollingpolicy.total-size-cap"))
    }

    // Loads through Spring Boot's own config-data loader, so profile order and operating-system-variable precedence are
    // the real ones. Only the classpath is searched, so ./config/application-local.yml on a developer PC is never read,
    // and the operating system's real variables are replaced by the given ones. spring.profiles.active is pinned to the given
    // profiles, as SPRING_PROFILES_ACTIVE is on a server, so application.yml's default of local does not join them.
    // No application context is started.
    private fun environment(profiles: List<String>, variables: Map<String, Any>): StandardEnvironment {
        val environment = StandardEnvironment()
        environment.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables),
        )
        environment.propertySources.replace(
            StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
            MapPropertySource(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                mapOf("spring.config.location" to "classpath:/", "spring.profiles.active" to profiles.joinToString(",")),
            ),
        )
        ConfigDataEnvironmentPostProcessor.applyTo(
            environment,
            DefaultResourceLoader(),
            DefaultBootstrapContext(),
            *profiles.toTypedArray(),
        )
        return environment
    }

    private companion object {
        const val DECU_WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        const val BINANCE_MIRROR = "https://data-api.binance.vision"
        val DATABASE_VARIABLES = mapOf(
            "DATABASE_URL" to "jdbc:postgresql://127.0.0.1:5432/trenching_bot",
            "DATABASE_USERNAME" to "db-user",
            "DATABASE_PASSWORD" to "db-password",
        )
    }
}
