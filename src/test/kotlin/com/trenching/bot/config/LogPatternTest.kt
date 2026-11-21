package com.trenching.bot.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.LoggingEvent
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.ansi.AnsiOutput
import org.springframework.boot.logging.logback.ColorConverter
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter
import org.springframework.core.io.ClassPathResource
import java.util.function.Supplier
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The log prefix in application.yml must keep Grafana Alloy's multiline and level stages (deploy/alloy/config.alloy) working. */
class LogPatternTest {
    private val firstLine = Regex("""^\d{4}-\d{2}-\d{2}T""")
    private val level = Regex("""^\S+\s+(?<level>TRACE|DEBUG|INFO|WARN|ERROR)\s""")
    private val context = LoggerContext()
    private val previousAnsi = AnsiOutput.getEnabled()

    @BeforeTest
    fun journalIsNotATerminal() = AnsiOutput.setEnabled(AnsiOutput.Enabled.NEVER)

    @AfterTest
    fun restoreAnsi() = AnsiOutput.setEnabled(previousAnsi)

    @Test
    fun `a warn line through the console pattern matches both Alloy stages`() {
        val line = format(configured(CONSOLE_PATTERN), Level.WARN, "Capture paused. requestsLast24h=12")

        assertTrue(firstLine.containsMatchIn(line), line)
        assertNotNull(level.find(line), line)
        assertContains(level.find(line)!!.groups["level"]!!.value, "WARN")
        assertContains(line, " WARN [scheduling-1] AutomationHolds: Capture paused. requestsLast24h=12")
    }

    @Test
    fun `every level is read by the Alloy level stage`() {
        val pattern = configured(CONSOLE_PATTERN)
        listOf(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR).forEach {
            val line = format(pattern, it, "Message.")
            assertContains(level.find(line)?.groups?.get("level")?.value.orEmpty(), it.toString(), message = line)
        }
    }

    @Test
    fun `the file pattern has the same shape without colours`() {
        val line = format(configured(FILE_PATTERN), Level.WARN, "Message.")

        assertTrue(firstLine.containsMatchIn(line), line)
        assertContains(line, " WARN [scheduling-1] AutomationHolds: Message.")
    }

    private fun configured(key: String): String {
        val factory = YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")) }
        return requireNotNull(factory.`object`?.getProperty(key)) { "$key is not set in application.yml" }
    }

    private fun format(pattern: String, level: Level, message: String): String {
        val layout = PatternLayout().apply {
            context = this@LogPatternTest.context
            this.pattern = pattern
            instanceConverterMap["clr"] = Supplier { ColorConverter() }
            instanceConverterMap["wEx"] = Supplier { ExtendedWhitespaceThrowableProxyConverter() }
            start()
        }
        val logger = context.getLogger("com.trenching.bot.console.automation.application.AutomationHolds")
        val event = LoggingEvent(logger.name, logger, level, message, null, null).apply { threadName = "scheduling-1" }
        return layout.doLayout(event)
    }

    private companion object {
        const val CONSOLE_PATTERN = "logging.pattern.console"
        const val FILE_PATTERN = "logging.pattern.file"
    }
}
