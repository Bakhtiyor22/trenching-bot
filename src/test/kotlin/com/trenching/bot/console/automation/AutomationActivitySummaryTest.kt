package com.trenching.bot.console.automation

import ch.qos.logback.classic.Level
import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.application.AutomationActivitySummary
import com.trenching.bot.console.automation.application.AutomationHolds
import com.trenching.bot.console.automation.application.AutomationItemLog
import com.trenching.bot.console.automation.domain.AutomationHoldContext
import com.trenching.bot.console.automation.domain.AutomationHoldKind
import com.trenching.bot.console.automation.domain.AutomationItemChange
import com.trenching.bot.console.automation.domain.AutomationItemEventDraft
import com.trenching.bot.console.automation.domain.AutomationItemEventKind
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationItemSubject
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.capture.MutableClock
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Plan Rule 6: the hourly line over a real database, a fixed clock, the item log's counts and the holds in force. */
class AutomationActivitySummaryTest : AutomationDatabaseTest() {
    private val clock = MutableClock(EVENING)
    private val settings = properties(captureEnabled = true)
    private val activityLog = AutomationItemLog(settings, clock)
    private val holds = AutomationHolds(clock)
    private val usage = FakeApiUsageReports().also { it.lastDay = 100_020 }
    private val subject = AutomationItemSubject("item-1", "mint-1", "wallet-1", null, Instant.parse("2026-11-20T21:14:03Z"), "session-1", null, null, 1841, null)

    private fun summary() = AutomationActivitySummary(activityLog, summaryTransaction, holds, usage, settings)

    private fun seed(vararg states: AutomationItemState) {
        sessions.save(AutomationRows.session("session-1", AutomationSessionState.OPEN, EVENING))
        states.forEachIndexed { index, state -> items.save(AutomationRows.item(index + 1, "session-1", state = state, nextActionAt = EVENING)) }
    }

    private fun found(id: String) = activityLog.announce(
        mapOf(id to subject.copy(itemId = id)),
        mapOf(id to AutomationItemChange(null, listOf(AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, toState = AutomationItemState.DISCOVERED)))),
    )

    private fun failed(code: String) = activityLog.announce(
        mapOf("item-1" to subject),
        mapOf(
            "item-1" to AutomationItemChange(
                null,
                listOf(
                    AutomationItemEventDraft(AutomationItemEventKind.ATTEMPT_FAILED, step = AutomationStep.MARKET, attempt = 2, failureCode = code),
                    AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURING, AutomationItemState.FAILED, AutomationStep.MARKET, attempt = 2),
                ),
            ),
        ),
    )

    private fun logged(block: (LogCapture) -> Unit) = LogCapture(AutomationActivitySummary::class.java.name, Level.DEBUG).use(block)

    @Test
    fun `while a hold is in force the summary is a warning that names the hold and the buys waiting`() {
        seed(AutomationItemState.BLOCKED_BUDGET, AutomationItemState.BLOCKED_BUDGET, AutomationItemState.SIMULATED)
        holds.observe(AutomationHoldKind.REQUEST_CAP, 2, 0, AutomationHoldContext(100_020, 100_000))
        found("item-a")
        found("item-b")
        clock.now = EVENING.plus(Duration.ofHours(1))

        logged { logs ->
            summary().log(clock.now)

            val event = logs.events.single()
            assertEquals(Level.WARN, event.level)
            assertTrue(
                event.formattedMessage.startsWith("Since $EVENING: capture paused by the request cap for 1h; 2 buys found, 0 captured, 2 waiting. found=2 backfillFound=0 captured=0 "),
                event.formattedMessage,
            )
            assertTrue(event.formattedMessage.contains("waitingForCap=2 inCapture=0 awaitingSweep=0 discoveryScans=0 requestsLast24h=100020 cap=${settings.maxRequestsPer24h} since=$EVENING"), event.formattedMessage)
        }
    }

    @Test
    fun `without a hold it is an info, and the failure codes are one key`() {
        seed(AutomationItemState.CAPTURING, AutomationItemState.CAPTURED)
        failed("OVER_CAP")
        failed("REQUESTS")
        clock.now = EVENING.plus(Duration.ofHours(1))

        logged { logs ->
            summary().log(clock.now)

            val event = logs.events.single()
            assertEquals(Level.INFO, event.level)
            assertTrue(event.formattedMessage.contains("2 failed; 1 in capture, 1 awaiting the sweep."), event.formattedMessage)
            assertTrue(event.formattedMessage.contains(" failed=2 "), event.formattedMessage)
            assertTrue(event.formattedMessage.contains(" failedCodes=OVER_CAP:1,REQUESTS:1 "), event.formattedMessage)
        }
    }

    @Test
    fun `an idle hour still logs, as an info with the discovery scans of the hour`() {
        seed(AutomationItemState.SIMULATED, AutomationItemState.FAILED)
        repeat(6) { activityLog.discoveryScanRan() }
        clock.now = EVENING.plus(Duration.ofHours(1))

        logged { logs ->
            summary().log(clock.now)

            val event = logs.events.single()
            assertEquals(Level.INFO, event.level)
            assertTrue(event.formattedMessage.startsWith("Since $EVENING: nothing new; 6 discovery scans. "), event.formattedMessage)
            assertTrue(event.formattedMessage.contains(" discoveryScans=6 "), event.formattedMessage)
        }
    }

    @Test
    fun `the scans are counted again each hour`() {
        seed(AutomationItemState.SIMULATED)
        val summary = summary()

        logged { logs ->
            repeat(6) { activityLog.discoveryScanRan() }
            clock.now = EVENING.plus(Duration.ofHours(1))
            summary.log(clock.now)
            clock.now = EVENING.plus(Duration.ofHours(2))
            summary.log(clock.now)

            val messages = logs.messages(Level.INFO)
            assertTrue(messages[0].contains(" discoveryScans=6 "), messages[0])
            assertTrue(messages[1].startsWith("Last hour: nothing new; 0 discovery scans."), messages[1])
        }
    }

    @Test
    fun `only the first summary says since, the next ones cover the last hour`() {
        seed(AutomationItemState.CAPTURED)
        val summary = summary()

        logged { logs ->
            clock.now = EVENING.plus(Duration.ofHours(1))
            summary.log(clock.now)
            clock.now = EVENING.plus(Duration.ofHours(2))
            summary.log(clock.now)

            val messages = logs.messages(Level.INFO)
            assertTrue(messages[0].startsWith("Since $EVENING: "), messages[0])
            assertTrue(messages[1].startsWith("Last hour: "), messages[1])
            assertTrue(messages[1].endsWith("since=${EVENING.plus(Duration.ofHours(1))}"), messages[1])
        }
    }

    @Test
    fun `a hold that stayed clear for the settle time is ended by the summary, which then reads as an info`() {
        seed(AutomationItemState.CAPTURED)
        holds.observe(AutomationHoldKind.REQUEST_CAP, 1, 0, AutomationHoldContext(100_020, 100_000))
        holds.observe(AutomationHoldKind.REQUEST_CAP, 0, 1, AutomationHoldContext(99_000, 100_000))
        clock.now = EVENING.plus(Duration.ofHours(1))

        LogCapture(AutomationHolds::class.java.name, Level.DEBUG).use { holdLogs ->
            logged { logs ->
                summary().log(clock.now)

                assertEquals(Level.INFO, logs.events.single().level)
                assertEquals(listOf("Capture resumed: the request cap has room again after 0s. requestsLast24h=99000 cap=100000"), holdLogs.messages(Level.INFO))
            }
        }
    }
}
