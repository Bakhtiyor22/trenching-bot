package com.trenching.bot.notification

import ch.qos.logback.classic.Level
import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.notification.application.NotificationDispatcher
import com.trenching.bot.notification.application.NotificationOutboxStore
import com.trenching.bot.notification.config.NotificationConfiguration
import com.trenching.bot.notification.config.NotificationDeliveryConfiguration
import com.trenching.bot.notification.config.NotificationProperties
import com.trenching.bot.notification.config.NotificationStartupLog
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor

/** Rule 10 and the wiring of an application without a database: the outbox is an in-memory store. */
class NotificationConfigurationTest {
    private val store = InMemoryNotificationOutboxStore()
    private val logs = LogCapture()
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(NotificationConfiguration::class.java, NotificationDeliveryConfiguration::class.java)
        .withBean("simulationClock", Clock::class.java, { Clock.systemUTC() })
        .withBean(NotificationOutboxStore::class.java, { store })
        .withBean(ApiUsageRecorder::class.java, { ApiUsageRecorder.NONE })

    @AfterTest
    fun detachLog() = logs.close()

    private val configured = arrayOf(
        "trenching.notification.telegram.bot-token=123456:FAKE-test-token-value",
        "trenching.notification.telegram.chat-id=-1001234567890",
    )

    @Test
    fun `without a token and chat the publisher stores nothing and nothing is delivered`() {
        runner.run { context ->
            context.getBean(NotificationPublisher::class.java).publish(notification())

            assertTrue(store.rows.isEmpty())
            assertTrue(context.getBeansOfType(NotificationDispatcher::class.java).isEmpty())
            assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java).isEmpty())
        }
    }

    @Test
    fun `blank values, as the empty environment defaults give, mean off`() {
        runner.withPropertyValues("trenching.notification.telegram.bot-token=", "trenching.notification.telegram.chat-id= ").run { context ->
            context.getBean(NotificationPublisher::class.java).publish(notification())

            assertTrue(store.rows.isEmpty())
            assertTrue(context.getBeansOfType(NotificationDispatcher::class.java).isEmpty())
        }
    }

    @Test
    fun `says once at startup that notifications are off`() {
        runner.run { context ->
            context.getBean(NotificationStartupLog::class.java).logSettings()

            val infos = logs.at(Level.INFO)
            assertEquals(1, infos.size)
            assertContains(infos.single(), "Notifications are off")
        }
    }

    @Test
    fun `with a token and chat the publisher stores, the dispatcher exists and scheduling is on`() {
        runner.withPropertyValues(*configured).run { context ->
            context.getBean(NotificationPublisher::class.java).publish(notification())

            assertEquals(1, store.rows.size)
            assertNotNull(context.getBean(NotificationDispatcher::class.java))
            assertEquals(1, context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java).size)
        }
    }

    @Test
    fun `says at startup that notifications are on without the token or chat`() {
        runner.withPropertyValues(*configured).run { context ->
            context.getBean(NotificationStartupLog::class.java).logSettings()

            val infos = logs.at(Level.INFO)
            assertEquals(1, infos.size)
            assertContains(infos.single(), "Notifications are on")
            assertFalse(logs.everything().contains("FAKE-test-token-value"))
            assertFalse(logs.everything().contains("1234567890"))
        }
    }

    @Test
    fun `a token without a chat id fails startup naming the property`() {
        runner.withPropertyValues("trenching.notification.telegram.bot-token=123456:FAKE-test-token-value").run { context ->
            assertContains(failureText(context), "trenching.notification.telegram.chat-id")
            assertFalse(failureText(context).contains("FAKE-test-token-value"))
        }
    }

    @Test
    fun `a chat id without a token fails startup naming the property`() {
        runner.withPropertyValues("trenching.notification.telegram.chat-id=-1001234567890").run { context ->
            assertContains(failureText(context), "trenching.notification.telegram.bot-token")
        }
    }

    @Test
    fun `retries a message for about a day by default`() {
        runner.run { context -> assertEquals(54, context.getBean(NotificationProperties::class.java).maxAttempts) }
    }

    @Test
    fun `the application file does not repeat the default kinds`() {
        val yaml = YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")) }.getObject()
            ?.stringPropertyNames().orEmpty()

        assertTrue(yaml.none { it.startsWith("trenching.notification.kinds") }, "kinds belongs to the class default")
    }

    @Test
    fun `a value out of bounds fails startup naming the property`() {
        runner.withPropertyValues(*configured, "trenching.notification.max-attempts=0").run { context ->
            assertContains(failureText(context), "trenching.notification.max-attempts")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://api.telegram.org", "http://localhost:8080", "http://127.0.0.1:1", "http://[::1]:9000/"])
    fun `accepts https anywhere and http only on this machine`(url: String) {
        val telegram = NotificationProperties.Telegram(botToken = TOKEN, chatId = "-1001234567890", apiBaseUrl = url)

        assertTrue(telegram.configured)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://localhost.example", "http://localhost.example:8080/x", "http://127.0.0.1.example", "http://localhostx", "http://[::1].example",
            "http://api.telegram.org", "ftp://localhost", "https://exa mple.com", "https://", "not a url", "http://localhost@evil.example",
            "https://user:pass@api.telegram.org",
        ],
    )
    fun `refuses an api base url that is not https or an exact local host, naming the property and never the token`(url: String) {
        val failure = assertFailsWith<IllegalArgumentException> {
            NotificationProperties.Telegram(botToken = TOKEN, chatId = "-1001234567890", apiBaseUrl = url)
        }

        assertContains(failure.message.orEmpty(), "trenching.notification.telegram.api-base-url")
        assertFalse(generateSequence<Throwable>(failure) { it.cause }.any { it.message.orEmpty().contains(TOKEN) })
    }

    @Test
    fun `a startup failure for a bad api base url never quotes the token`() {
        runner.withPropertyValues(*configured, "trenching.notification.telegram.api-base-url=https://exa mple.com").run { context ->
            assertContains(failureText(context), "trenching.notification.telegram.api-base-url")
            assertFalse(failureText(context).contains("FAKE-test-token-value"))
        }
    }

    @Test
    fun `the properties never print the token`() {
        runner.withPropertyValues(*configured).run { context ->
            val properties = context.getBean(NotificationProperties::class.java)

            assertFalse(properties.toString().contains("FAKE-test-token-value"))
            assertFalse(properties.telegram.toString().contains("FAKE-test-token-value"))
        }
    }

    private fun failureText(context: ApplicationContext): String {
        val failure = (context as org.springframework.boot.test.context.assertj.AssertableApplicationContext).startupFailure
        assertNotNull(failure, "the application context should have failed to start")
        return generateSequence<Throwable>(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
    }

    private companion object {
        const val TOKEN = "123456:FAKE-test-token-value"
    }

    private fun notification() =
        OutboundNotification("BUY_RESULT|item-1", NotificationKind.BUY_RESULT, "Buy", listOf("a"), listOf(NotificationLink("Open", "/buys/1")))
}
