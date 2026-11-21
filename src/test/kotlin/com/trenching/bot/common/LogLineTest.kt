package com.trenching.bot.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class LogLineTest {
    private val logger = (LoggerFactory.getLogger(LogLineTest::class.java) as Logger).apply { level = Level.DEBUG }

    private fun logged(write: () -> Unit): List<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try { write() } finally { logger.detachAppender(appender) }
        return appender.list
    }

    @Test
    fun `present fields follow the sentence in order and absent ones are left out`() {
        val events = logged {
            LogLine("Window stopped: {}.", "the provider is rate limiting").field("code", "RATE_LIMITED").field("mint", null).field("requests", 7).warn(logger)
        }

        val event = events.single()
        assertEquals(Level.WARN, event.level)
        assertEquals("Window stopped: the provider is rate limiting. code=RATE_LIMITED requests=7", event.formattedMessage)
        assertEquals("Window stopped: {}. code={} requests={}", event.message)
    }

    @Test
    fun `a line with no fields is just its sentence`() {
        assertEquals("Done.", logged { LogLine("Done.").field("mint", null).debug(logger) }.single().formattedMessage)
    }

    @Test
    fun `an error line carries its cause as the stack`() {
        val cause = IllegalStateException("fixed")

        val event = logged { LogLine("Failed.").field("step", "market").error(logger, cause) }.single()

        assertEquals(Level.ERROR, event.level)
        assertEquals("Failed. step=market", event.formattedMessage)
        assertEquals("fixed", assertNotNull(event.throwableProxy).message)
    }
}
