package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationFixtures.buy
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.application.AutomationManualRun
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.domain.AutomationDisabledException
import com.trenching.bot.console.automation.domain.ManualRunResult
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Plan Rules 29 and Key Decision 2: "run now" queues at most one pass on the scheduling thread, ignoring the window. */
class AutomationManualRunTest : AutomationDatabaseTest() {
    /** 14:00 local, outside the default polling window. */
    private val afternoon = Instant.parse("2026-11-02T09:00:00Z")
    private val clock = MutableClock(afternoon)
    private val lookup = FakeWalletActivityLookup()
    private val scheduler = RecordingTaskScheduler()

    @Test
    fun `two requests queue one pass, and the request itself scans nothing`() {
        val run = manualRun(properties())

        val first = run.request()
        val second = run.request()

        assertEquals(ManualRunResult.QUEUED, first)
        assertEquals(ManualRunResult.ALREADY_QUEUED, second)
        assertEquals(1, scheduler.tasks.size, "the second request adds nothing")
        assertEquals(listOf(afternoon), scheduler.starts)
        assertTrue(lookup.requests.isEmpty(), "the scan waits for the scheduling thread")
        assertTrue(runtime.manualRunQueued())
    }

    @Test
    fun `the queued pass scans outside the window, then a new request queues another`() {
        lookup.actions += buy(1, 100, afternoon.minusSeconds(300))
        val run = manualRun(properties())
        run.request()

        scheduler.runNext()

        assertEquals(1, items.count())
        assertEquals(afternoon, runtime.heartbeat().lastDiscoveryAt)
        assertFalse(runtime.manualRunQueued())
        assertEquals(ManualRunResult.QUEUED, run.request())
        assertEquals(1, scheduler.tasks.size)
    }

    @Test
    fun `a pass the provider cannot serve skips like a scheduled one and frees the next request`() {
        lookup.unavailable = WalletActivityUnavailable.PROVIDER_RATE_LIMITED
        val run = manualRun(properties())
        run.request()

        scheduler.runNext()

        assertEquals(0, items.count())
        assertEquals(WalletActivityUnavailable.PROVIDER_RATE_LIMITED, runtime.heartbeat().discoveryProblem)
        assertEquals(ManualRunResult.QUEUED, run.request())
    }

    @Test
    fun `an unexpected failure of the pass is contained and frees the next request`() {
        val run = manualRun(properties())
        run.request()
        lookup.failure = IllegalStateException("boom")

        scheduler.runNext()

        assertFalse(runtime.manualRunQueued())
        assertEquals(ManualRunResult.QUEUED, run.request())
    }

    @Test
    fun `with automation off a request is refused and nothing is queued`() {
        val off = AutomationProperties(enabled = false)
        val run = AutomationManualRun(off, runtime, null, null, clock)

        assertFailsWith<AutomationDisabledException> { run.request() }
        assertFalse(runtime.manualRunQueued())
    }

    private fun manualRun(properties: AutomationProperties) = AutomationManualRun(properties, runtime, discovery(lookup, properties, clock), scheduler, clock)
}
