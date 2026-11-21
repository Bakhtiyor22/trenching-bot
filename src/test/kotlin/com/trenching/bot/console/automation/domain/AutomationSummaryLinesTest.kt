package com.trenching.bot.console.automation.domain

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** The hourly summary's words and keys (plan Rule 6). */
class AutomationSummaryLinesTest {
    private val since = Instant.parse("2026-11-18T22:41:07Z")
    private val now = since.plusSeconds(3600)
    private val counts = AutomationActivityCounts(since, now)
    private val capHold = AutomationHold(AutomationHoldKind.REQUEST_CAP, now.minusSeconds(5 * 3600 + 12 * 60), 39, AutomationHoldContext(100_020, 100_000))

    private fun line(
        counts: AutomationActivityCounts = this.counts,
        waiting: Map<AutomationItemState, Long> = emptyMap(),
        holds: List<AutomationHold> = emptyList(),
        sinceStart: Boolean = false,
        captureEnabled: Boolean = true,
    ) = AutomationSummaryLines.of(counts, waiting, holds, 100_020, 100_000, captureEnabled, sinceStart, now)

    private fun AutomationSummaryLine.text(): String =
        arguments.fold(format) { text, argument -> text.replaceFirst("{}", argument.toString()) } + fields.filter { it.second != null }.joinToString("") { " ${it.first}=${it.second}" }

    @Test
    fun `a hold in force makes a warning that names the hold and how long it has lasted`() {
        val line = line(counts.copy(liveBuysFound = 2), mapOf(AutomationItemState.BLOCKED_BUDGET to 39L), listOf(capHold))

        assertEquals(AutomationLogLevel.WARN, line.level)
        assertEquals(
            "Last hour: capture paused by the request cap for 5h12m; 2 buys found, 0 captured, 39 waiting. found=2 backfillFound=0 captured=0 skipped=0 failed=0 " +
                "simulated=0 retried=0 waitingForCap=39 inCapture=0 awaitingSweep=0 discoveryScans=0 requestsLast24h=100020 cap=100000 since=2026-11-18T22:41:07Z",
            line.text(),
        )
    }

    @Test
    fun `without a hold the summary is an info with what was done and what is in capture`() {
        val done = counts.copy(liveBuysFound = 3, captured = 2, failedByCode = mapOf("REQUESTS" to 1))
        val waiting = mapOf(AutomationItemState.CAPTURING to 3L, AutomationItemState.WAITING to 1L)

        val line = line(done, waiting)

        assertEquals(AutomationLogLevel.INFO, line.level)
        assertTrue(line.text().startsWith("Last hour: 3 buys found, 2 captured, 1 failed; 4 in capture. found=3 "), line.text())
        assertTrue(line.text().contains(" inCapture=4 awaitingSweep=0 "), line.text())
    }

    @Test
    fun `items awaiting the sweep and a single buy are said in words`() {
        val line = line(counts.copy(backfillBuysFound = 1), mapOf(AutomationItemState.CAPTURED to 2L, AutomationItemState.DISCOVERED to 1L))

        assertTrue(line.text().startsWith("Last hour: 1 buy found, 0 captured; 1 in capture, 2 awaiting the sweep. found=0 backfillFound=1 "), line.text())
    }

    @Test
    fun `the failure codes are one key without spaces, sorted by code`() {
        val line = line(counts.copy(failedByCode = mapOf("REQUESTS" to 1, "OVER_CAP" to 1)))

        assertTrue(line.text().contains(" failed=2 "), line.text())
        assertTrue(line.text().contains(" failedCodes=OVER_CAP:1,REQUESTS:1 "), line.text())
    }

    @Test
    fun `no failure code key when nothing failed`() {
        assertTrue(line(counts.copy(captured = 1)).fields.none { it.first == "failedCodes" && it.second != null })
    }

    @Test
    fun `skipped, simulated and retried show in the sentence only when there are some`() {
        val line = line(counts.copy(liveBuysFound = 1, skipped = 1, simulated = 2, retriedAttempts = 3))

        assertTrue(line.text().startsWith("Last hour: 1 buy found, 0 captured, 1 skipped, 2 simulated, 3 retried. "), line.text())
    }

    @Test
    fun `the first summary after a start says since when`() {
        val line = line(counts.copy(captured = 1), sinceStart = true)

        assertTrue(line.text().startsWith("Since 2026-11-18T22:41:07Z: 0 buys found, 1 captured."), line.text())
        assertTrue(line.text().endsWith("since=2026-11-18T22:41:07Z"), line.text())
    }

    @Test
    fun `an idle hour is still said, as an info with the discovery scans that ran`() {
        val idle = line(counts.copy(discoveryScans = 6))

        assertEquals(AutomationLogLevel.INFO, idle.level)
        assertTrue(idle.text().startsWith("Last hour: nothing new; 6 discovery scans. found=0 "), idle.text())
        assertTrue(idle.text().contains(" discoveryScans=6 "), idle.text())
        assertTrue(line(counts.copy(discoveryScans = 1), mapOf(AutomationItemState.BLOCKED_BUDGET to 0L)).text().startsWith("Last hour: nothing new; 1 discovery scan. "))
        assertTrue(line(counts, sinceStart = true).text().startsWith("Since 2026-11-18T22:41:07Z: nothing new; 0 discovery scans."))
    }

    @Test
    fun `discovery scans are a key of a busy hour too and never make an idle hour busy`() {
        assertTrue(line(counts.copy(captured = 1, discoveryScans = 6)).text().contains(" discoveryScans=6 "))
        assertTrue(line(counts.copy(discoveryScans = 6)).text().contains("nothing new"))
    }

    @Test
    fun `with capture switched off the discovered buys wait for it to be switched on, they are not in capture`() {
        val waiting = mapOf(AutomationItemState.DISCOVERED to 3L, AutomationItemState.CAPTURED to 1L)

        val off = line(waiting = waiting, captureEnabled = false)
        val on = line(waiting = waiting)

        assertTrue(off.text().contains("1 awaiting the sweep, 3 waiting for capture to be switched on."), off.text())
        assertTrue(off.text().contains(" inCapture=0 awaitingSweep=1 awaitingCaptureOn=3 "), off.text())
        assertTrue(on.text().contains("3 in capture, 1 awaiting the sweep."), on.text())
        assertTrue(on.fields.none { it.first == "awaitingCaptureOn" && it.second != null }, on.text())
    }

    @Test
    fun `a hold alone, or one waiting item alone, is enough to say something`() {
        assertEquals(AutomationLogLevel.WARN, line(holds = listOf(capHold)).level)
        assertEquals(AutomationLogLevel.INFO, line(waiting = mapOf(AutomationItemState.CAPTURED to 1L)).level)
    }

    @Test
    fun `two holds are both named`() {
        val reserve = AutomationHold(AutomationHoldKind.BACKFILL_RESERVE, now.minusSeconds(600), 2, AutomationHoldContext())

        val line = line(holds = listOf(capHold, reserve))

        assertTrue(
            line.text().startsWith("Last hour: capture paused by the request cap for 5h12m and backfill capture paused to keep the live reserve for 10m; 0 buys found, 0 captured."),
            line.text(),
        )
    }
}
