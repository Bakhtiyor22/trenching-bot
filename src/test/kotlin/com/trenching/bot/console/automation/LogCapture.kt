package com.trenching.bot.console.automation

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Collects what a package's loggers write while a test runs, at [level] whatever the test profile sets for `com.trenching.bot`; closing it puts
 * the logger back as it was.
 */
class LogCapture(loggerName: String = AUTOMATION_PACKAGE, level: Level = Level.DEBUG) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(loggerName) as Logger
    private val previousLevel = logger.level
    private val appender = ListAppender<ILoggingEvent>().also { it.start() }

    init {
        logger.level = level
        logger.addAppender(appender)
    }

    val events: List<ILoggingEvent> get() = appender.list.toList()

    fun at(level: Level): List<ILoggingEvent> = events.filter { it.level == level }

    fun messages(level: Level): List<String> = at(level).map { it.formattedMessage }

    fun clear() = appender.list.clear()

    override fun close() {
        logger.detachAppender(appender)
        logger.level = previousLevel
    }

    companion object {
        const val AUTOMATION_PACKAGE = "com.trenching.bot.console.automation"
    }
}
