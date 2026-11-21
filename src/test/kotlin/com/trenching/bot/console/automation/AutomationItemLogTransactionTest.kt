package com.trenching.bot.console.automation

import ch.qos.logback.classic.Level
import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.domain.AutomationItemChange
import com.trenching.bot.console.automation.application.AutomationItemEventWriter
import com.trenching.bot.console.automation.application.AutomationItemLog
import com.trenching.bot.console.automation.domain.AutomationItemEvents
import com.trenching.bot.console.automation.domain.AutomationItemSubject
import com.trenching.bot.console.automation.domain.AutomationSessionState
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** An item's lines and counts are written once the transaction that stores its change has committed, never before and never after a rollback. */
class AutomationItemLogTransactionTest : AutomationDatabaseTest() {
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val template get() = TransactionTemplate(transactionManager)

    @BeforeEach
    fun forgetEarlierTests() {
        itemLog.drain(EVENING)
    }

    private fun seed() {
        sessions.save(AutomationRows.session("session-1", AutomationSessionState.OPEN, EVENING))
        items.save(AutomationRows.item(1, "session-1", blockTime = EVENING))
    }

    private fun foundEvents() = AutomationItemChange.found(AutomationItemEvents.created(records.toProgress(items.findOneById("item-1")!!)))

    @Test
    fun `a stored change logs its line after the transaction commits and counts it once`() {
        seed()
        LogCapture().use { capture ->
            template.execute {
                itemEventWriter.append("item-1", EVENING, foundEvents())
                assertTrue(capture.events.isEmpty(), "nothing is said while the change can still roll back")
            }

            val line = capture.at(Level.INFO).single().formattedMessage
            assertTrue(line.startsWith("Buy found. "), line)
            assertTrue(line.contains("sessionId=session-1"), line)
            assertEquals(1, itemLog.drain(EVENING).liveBuysFound)
        }
    }

    @Test
    fun `a failure while the lines are built is logged once and never rolls the stored change back`() {
        seed()
        val failing = object : AutomationItemLog(AutomationFixtures.properties(), Clock.systemUTC()) {
            override fun announce(subjects: Map<String, AutomationItemSubject>, changes: Map<String, AutomationItemChange>) =
                throw IllegalStateException("https://rpc.example/?api-key=secret")
        }
        val writer = AutomationItemEventWriter(itemEvents, items, sweepRuns, failing)

        LogCapture().use { capture ->
            template.execute { writer.append("item-1", EVENING, foundEvents()) }

            assertTrue(itemEvents.count() > 0, "the change and its event are stored")
            val warning = capture.at(Level.WARN).single().formattedMessage
            assertTrue(warning.startsWith("The log lines of stored buy changes could not be built; "), warning)
            assertTrue(warning.contains("itemIds=item-1 cause=IllegalStateException"), warning)
            assertTrue(!warning.contains("secret"), warning)
            assertTrue(capture.at(Level.INFO).isEmpty())
        }
    }

    @Test
    fun `a change that is rolled back logs no line and counts nothing`() {
        seed()
        LogCapture().use { capture ->
            template.execute { status ->
                itemEventWriter.append("item-1", EVENING, foundEvents())
                status.setRollbackOnly()
            }

            assertTrue(capture.events.isEmpty(), capture.events.map { it.formattedMessage }.toString())
            assertTrue(itemLog.drain(EVENING).isEmpty)
            assertEquals(0, itemEvents.count())
        }
    }

    @Test
    fun `a change an optimistic conflict fails at commit logs no line and counts nothing`() {
        seed()
        LogCapture().use { capture ->
            assertFailsWith<OptimisticLockingFailureException> {
                template.execute {
                    itemEventWriter.append("item-1", EVENING, foundEvents())
                    val session = sessions.findOneById("session-1") ?: error("seeded")
                    jdbc.update("update automation_sessions set version = version + 1 where id = ?", "session-1")
                    session.belowFilterCount += 1
                }
            }

            assertTrue(capture.events.isEmpty(), capture.events.map { it.formattedMessage }.toString())
            assertTrue(itemLog.drain(EVENING).isEmpty)
            assertEquals(0, itemEvents.count())
        }
    }
}
