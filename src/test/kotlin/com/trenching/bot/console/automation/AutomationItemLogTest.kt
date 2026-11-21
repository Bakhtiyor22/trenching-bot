package com.trenching.bot.console.automation

import ch.qos.logback.classic.Level
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.application.AutomationItemLog
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationItemChange
import com.trenching.bot.console.automation.domain.AutomationItemEventDraft
import com.trenching.bot.console.automation.domain.AutomationItemEventKind
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationItemSubject
import com.trenching.bot.console.automation.domain.AutomationStep
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** The component that writes and counts an item's lines, outside a transaction, where a line is written at once. */
class AutomationItemLogTest {
    private val start = Instant.parse("2026-11-21T10:00:00Z")
    private val live = AutomationItemSubject("item-1", "mint-1", "wallet-1", null, Instant.parse("2026-11-20T21:14:03Z"), "session-1", null, null, 1841, null)
    private val log = AutomationItemLog(properties(), Clock.fixed(start, ZoneOffset.UTC))

    private fun announce(subject: AutomationItemSubject, vararg events: AutomationItemEventDraft) =
        log.announce(mapOf(subject.itemId to subject), mapOf(subject.itemId to AutomationItemChange(null, events.toList())))

    private fun found() = AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, toState = AutomationItemState.DISCOVERED)

    private fun attemptFailed(code: String, attempt: Int = 1) =
        AutomationItemEventDraft(AutomationItemEventKind.ATTEMPT_FAILED, step = AutomationStep.MARKET, attempt = attempt, failureCode = code)

    private fun failedState() =
        AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURING, AutomationItemState.FAILED, AutomationStep.MARKET, attempt = 2)

    @Test
    fun `a live buy found is one info line from one logger, with its context and no backfill key`() {
        LogCapture().use { capture ->
            announce(live, found())

            val event = capture.events.single()
            assertEquals(Level.INFO, event.level)
            assertEquals("com.trenching.bot.console.automation.application.AutomationItemLog", event.loggerName)
            assertTrue(event.formattedMessage.startsWith("Buy found. buyAt=2026-11-20T21:14:03Z tradingDay=2026-11-20 sessionId=session-1 "), event.formattedMessage)
            assertTrue(event.formattedMessage.endsWith("itemId=item-1 mint=mint-1 wallet=wallet-1"), event.formattedMessage)
            assertFalse(event.formattedMessage.contains("backfillId"), event.formattedMessage)
            assertFalse(event.formattedMessage.contains("null"), event.formattedMessage)
        }
    }

    @Test
    fun `a backfill's buy found is debug and names its backfill`() {
        LogCapture().use { capture ->
            announce(live.copy(backfillId = "backfill-1"), found())

            assertEquals(listOf(Level.DEBUG), capture.events.map { it.level })
            assertTrue(capture.events.single().formattedMessage.contains("backfillId=backfill-1"))
        }
    }

    @Test
    fun `a failed first attempt and a failed last attempt each give one warn with their attempt`() {
        LogCapture().use { capture ->
            announce(live, attemptFailed("REQUESTS"))
            announce(live, attemptFailed("OVER_CAP", attempt = 2), failedState())

            val warnings = capture.messages(Level.WARN)
            assertEquals(2, warnings.size)
            assertTrue(warnings[0].startsWith("Buy capture will retry the market step: the operation's request budget ran out. code=REQUESTS step=market attempt=1/2 "), warnings[0])
            assertTrue(warnings[1].startsWith("Buy failed at the market step and will not be retried: "), warnings[1])
            assertTrue(warnings[1].contains("code=OVER_CAP step=market attempt=2/2 requestsUsed=1841 itemId=item-1"), warnings[1])
        }
    }

    @Test
    fun `an unknown code still logs with the fallback clause and the raw code`() {
        LogCapture().use { capture ->
            announce(live, attemptFailed("SOMETHING_NEW"))

            val message = capture.messages(Level.WARN).single()
            assertTrue(message.contains(AutomationFailures.meaning("SOMETHING_NEW")), message)
            assertTrue(message.contains("code=SOMETHING_NEW"), message)
        }
    }

    @Test
    fun `a change of an item the transaction no longer holds says nothing`() {
        LogCapture().use { capture ->
            log.announce(emptyMap(), mapOf("gone" to AutomationItemChange(null, listOf(found()))))

            assertTrue(capture.events.isEmpty())
            assertTrue(log.drain(start.plusSeconds(1)).isEmpty)
        }
    }

    @Test
    fun `what was logged is counted and a drain returns the counts since the last one and starts again from zero`() {
        LogCapture().use {
            announce(live, found())
            announce(live.copy(itemId = "item-2", backfillId = "backfill-1"), found())
            announce(live, attemptFailed("REQUESTS"))
            announce(live, attemptFailed("OVER_CAP"), failedState())
            announce(live, attemptFailed("OVER_CAP", attempt = 2), failedState())
            announce(live, AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURING, AutomationItemState.CAPTURED, AutomationStep.REGISTER))
            announce(live.copy(sweepRuns = 3), AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURED, AutomationItemState.SIMULATED, AutomationStep.SWEEP))
            announce(live, AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURING, AutomationItemState.ABANDONED, AutomationStep.WALLET))
            announce(live, AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURING, AutomationItemState.SKIPPED_NOT_FIRST_BUY, AutomationStep.QUALIFY))
            announce(live, AutomationItemEventDraft(AutomationItemEventKind.RETRY_REQUESTED, AutomationItemState.FAILED, AutomationItemState.CAPTURING, AutomationStep.MARKET, requestId = "r"))
            val later = start.plus(Duration.ofHours(1))

            val first = log.drain(later)
            val second = log.drain(later.plus(Duration.ofHours(1)))

            assertEquals(start, first.since)
            assertEquals(later, first.until)
            assertEquals(1, first.liveBuysFound)
            assertEquals(1, first.backfillBuysFound)
            assertEquals(1, first.retriedAttempts)
            assertEquals(mapOf("OVER_CAP" to 2), first.failedByCode)
            assertEquals(2, first.failed)
            assertEquals(1, first.captured)
            assertEquals(1, first.simulated)
            assertEquals(1, first.abandoned)
            assertEquals(1, first.skipped)
            assertEquals(1, first.retriedByPerson)
            assertEquals(later, second.since, "the next window starts where the last one ended")
            assertTrue(second.isEmpty)
        }
    }

    @Test
    fun `counts stay exact when the scheduler and console threads log at once`() {
        LogCapture(level = Level.ERROR).use {
            val pool = Executors.newFixedThreadPool(8)
            try {
                (1..8).map { pool.submit { repeat(250) { announce(live, found()) } } }.forEach { it.get() }
            } finally {
                pool.shutdown()
            }

            assertEquals(2000, log.drain(start.plusSeconds(5)).liveBuysFound)
        }
    }
}
