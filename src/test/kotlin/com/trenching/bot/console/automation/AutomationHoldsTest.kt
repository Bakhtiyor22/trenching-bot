package com.trenching.bot.console.automation

import ch.qos.logback.classic.Level
import com.trenching.bot.console.automation.application.AutomationHolds
import com.trenching.bot.console.automation.domain.AutomationHold
import com.trenching.bot.console.automation.domain.AutomationHoldContext
import com.trenching.bot.console.automation.domain.AutomationHoldKind
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.capture.MutableClock
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Plan Rule 5: one line when a hold starts and one when it ends, whatever the number of held items or ticks, and no flapping at the cap. */
class AutomationHoldsTest {
    private val start = Instant.parse("2026-11-18T22:41:07Z")
    private val clock = MutableClock(start)
    private val holds = AutomationHolds(clock)
    private val atCap = AutomationHoldContext(requestsLast24h = 100_012, cap = 100_000)

    private fun hold(kind: AutomationHoldKind = AutomationHoldKind.REQUEST_CAP, held: Int = 1, context: AutomationHoldContext = atCap) =
        holds.observe(kind, held, 0, context)

    private fun admit(kind: AutomationHoldKind = AutomationHoldKind.REQUEST_CAP, context: AutomationHoldContext = atCap) =
        holds.observe(kind, 0, 1, context)

    private fun idle(kind: AutomationHoldKind = AutomationHoldKind.REQUEST_CAP) = holds.observe(kind, 0, 0, AutomationHoldContext.NONE)

    private fun after(duration: Duration) {
        clock.now = clock.now.plus(duration)
    }

    private fun logged(block: (LogCapture) -> Unit) = LogCapture(AutomationHolds::class.java.name, Level.DEBUG).use(block)

    @Test
    fun `100 items held in one tick give exactly one warning with the numbers`() {
        logged { logs ->
            hold(held = 100)

            val warnings = logs.messages(Level.WARN)
            assertEquals(
                listOf("Capture paused: the 24-hour request cap is used up; waiting buys go on as older requests age out. requestsLast24h=100012 cap=100000 waiting=100"),
                warnings,
            )
            assertEquals(1, logs.events.size)
        }
    }

    @Test
    fun `held on 60 ticks in a row gives one warning`() {
        logged { logs ->
            repeat(60) {
                hold(held = it + 1)
                after(Duration.ofMinutes(1))
            }

            assertEquals(1, logs.events.size, logs.events.toString())
            assertEquals(Level.WARN, logs.events.single().level)
        }
    }

    @Test
    fun `admitted and held ticks alternating for hours still give one warning and no info`() {
        logged { logs ->
            hold()
            repeat(300) {
                after(Duration.ofMinutes(1))
                admit()
                after(Duration.ofMinutes(1))
                hold()
            }

            assertEquals(listOf(Level.WARN), logs.events.map { it.level })
            assertEquals(listOf(AutomationHoldKind.REQUEST_CAP), holds.current().map { it.kind })
        }
    }

    @Test
    fun `a hold that stays clear for the settle time ends with one info that says how long capture was held`() {
        logged { logs ->
            hold()
            after(Duration.ofHours(2).plusMinutes(31))
            admit(context = AutomationHoldContext(requestsLast24h = 99_410, cap = 100_000))
            assertEquals(1, logs.events.size, "clearing logs nothing")

            after(AutomationLimits.HOLD_SETTLE.minusSeconds(1))
            idle()
            assertEquals(1, logs.events.size, "one second short of the settle time")

            after(Duration.ofSeconds(1))
            idle()

            assertEquals(listOf(Level.WARN, Level.INFO), logs.events.map { it.level })
            assertEquals("Capture resumed: the request cap has room again after 2h31m. requestsLast24h=99410 cap=100000", logs.messages(Level.INFO).single())
            assertEquals(emptyList(), holds.current())
        }
    }

    @Test
    fun `the settle time is counted again from the last held tick`() {
        logged { logs ->
            hold()
            admit()
            after(Duration.ofMinutes(59))
            hold()
            admit()
            after(Duration.ofMinutes(59))
            idle()

            assertEquals(listOf(Level.WARN), logs.events.map { it.level })

            after(Duration.ofMinutes(1))
            idle()

            assertEquals(listOf(Level.WARN, Level.INFO), logs.events.map { it.level })
        }
    }

    @Test
    fun `a hold that settled and then holds again is a new hold with its own warning`() {
        logged { logs ->
            hold()
            admit()
            after(AutomationLimits.HOLD_SETTLE)
            hold()

            assertEquals(listOf(Level.WARN, Level.INFO, Level.WARN), logs.events.map { it.level })
            assertEquals(clock.now, holds.current().single().since)
        }
    }

    @Test
    fun `a tick with nothing held and nothing admitted changes nothing`() {
        logged { logs ->
            idle()
            hold()
            after(Duration.ofHours(5))
            idle()

            assertEquals(listOf(Level.WARN), logs.events.map { it.level })
            assertEquals(1, holds.current().size, "still in force: nothing was admitted")
        }
    }

    @Test
    fun `admitted items with no hold in force log nothing`() {
        logged { logs ->
            admit()
            admit(AutomationHoldKind.BACKFILL_RESERVE)

            assertEquals(emptyList(), logs.events)
            assertEquals(emptyList(), holds.current())
        }
    }

    @Test
    fun `the request cap and the backfill reserve are independent holds`() {
        logged { logs ->
            val reserve = AutomationHoldContext(requestsLast24h = 98_700, cap = 100_000, reserveRequests = 1400)

            holds.observe(AutomationHoldKind.REQUEST_CAP, 0, 3, atCap)
            holds.observe(AutomationHoldKind.BACKFILL_RESERVE, 5, 0, reserve)

            assertEquals(
                listOf("Backfill capture paused: only the live reserve is left under the request cap. requestsLast24h=98700 cap=100000 reserveRequests=1400 waiting=5"),
                logs.messages(Level.WARN),
            )
            assertEquals(listOf(AutomationHoldKind.BACKFILL_RESERVE), holds.current().map { it.kind })
        }
    }

    @Test
    fun `the backfill scan has a hold of its own, apart from the pipeline's backfill reserve`() {
        logged { logs ->
            holds.observe(AutomationHoldKind.BACKFILL_RESERVE, 2, 0, atCap)
            holds.observe(AutomationHoldKind.BACKFILL_SCAN_RESERVE, 1, 0, atCap)
            holds.observe(AutomationHoldKind.BACKFILL_RESERVE, 0, 1, atCap)
            holds.observe(AutomationHoldKind.BACKFILL_SCAN_RESERVE, 1, 0, atCap)

            assertEquals(2, logs.at(Level.WARN).size)
            assertTrue(logs.messages(Level.WARN)[1].startsWith("Backfill scan paused: "), logs.messages(Level.WARN)[1])
            assertEquals(listOf(AutomationHoldKind.BACKFILL_SCAN_RESERVE), holds.current().map { it.kind }, "the pipeline's reserve is clearing, the scan's is not")
        }
    }

    @Test
    fun `a console that is not configured says why once, however many ticks`() {
        logged { logs ->
            val reason = AutomationHoldContext(reason = "Configure SOLANA_RPC_URL in the backend process")

            repeat(60) { holds.observe(AutomationHoldKind.RPC_NOT_CONFIGURED, 3, 0, reason) }

            assertEquals(
                listOf("Capture paused: the capture console is not configured (Configure SOLANA_RPC_URL in the backend process). waiting=3"),
                logs.messages(Level.WARN),
            )
        }
    }

    @Test
    fun `a missing RPC URL and switched off rates are holds of their own, so one being admitted does not end the other`() {
        logged { logs ->
            holds.observe(AutomationHoldKind.RATES_NOT_CONFIGURED, 1, 0, AutomationHoldContext(reason = "Reference rate capture is disabled"))
            holds.observe(AutomationHoldKind.RPC_NOT_CONFIGURED, 0, 1, AutomationHoldContext.NONE)
            after(AutomationLimits.HOLD_SETTLE.plusSeconds(1))
            holds.observe(AutomationHoldKind.RPC_NOT_CONFIGURED, 0, 1, AutomationHoldContext.NONE)
            holds.settle(clock.now)

            assertEquals(
                listOf("Capture paused: the capture console is not configured (Reference rate capture is disabled). waiting=1"),
                logs.messages(Level.WARN),
            )
            assertEquals(emptyList(), logs.at(Level.INFO))
            assertEquals(listOf(AutomationHoldKind.RATES_NOT_CONFIGURED), holds.current().map { it.kind })
        }
    }

    @Test
    fun `a console refusal of each step maps to its own hold, and a step the console never refuses for configuration to none`() {
        assertEquals(AutomationHoldKind.RPC_NOT_CONFIGURED, AutomationHoldKind.notConfiguredFor(AutomationStep.WALLET))
        assertEquals(AutomationHoldKind.RPC_NOT_CONFIGURED, AutomationHoldKind.notConfiguredFor(AutomationStep.MARKET))
        assertEquals(AutomationHoldKind.RATES_NOT_CONFIGURED, AutomationHoldKind.notConfiguredFor(AutomationStep.RATES))
        assertEquals(null, AutomationHoldKind.notConfiguredFor(AutomationStep.EXPORT))
    }

    @Test
    fun `with no item held and none still blocked, a cap hold starts clearing and ends after the settle time, though nothing was admitted`() {
        logged { logs ->
            hold()
            holds.observe(AutomationHoldKind.REQUEST_CAP, 0, 0, AutomationHoldContext.NONE, blockedDue = 0)
            assertEquals(emptyList(), holds.current(), "clearing: no longer in force")
            after(AutomationLimits.HOLD_SETTLE)
            holds.settle(clock.now)

            assertEquals(listOf(Level.WARN, Level.INFO), logs.events.map { it.level })
            assertTrue(logs.messages(Level.INFO).single().startsWith("Capture resumed: the request cap has room again after "), logs.messages(Level.INFO).single())
        }
    }

    @Test
    fun `a due item still blocked keeps the hold in force on a tick that held and admitted none`() {
        logged { logs ->
            hold()
            repeat(3) {
                after(Duration.ofHours(1))
                holds.observe(AutomationHoldKind.REQUEST_CAP, 0, 0, AutomationHoldContext.NONE, blockedDue = 2)
            }

            assertEquals(listOf(Level.WARN), logs.events.map { it.level })
            assertEquals(listOf(AutomationHoldKind.REQUEST_CAP), holds.current().map { it.kind })
        }
    }

    @Test
    fun `current lists the holds in force with their start and latest numbers, and leaves out a clearing hold`() {
        hold(held = 4)
        after(Duration.ofHours(1))
        holds.observe(AutomationHoldKind.REQUEST_CAP, 7, 0, AutomationHoldContext(requestsLast24h = 100_050, cap = 100_000))
        holds.observe(AutomationHoldKind.BACKFILL_RESERVE, 2, 0, atCap)
        admit(AutomationHoldKind.BACKFILL_RESERVE)

        assertEquals(
            listOf(AutomationHold(AutomationHoldKind.REQUEST_CAP, start, 7, AutomationHoldContext(requestsLast24h = 100_050, cap = 100_000))),
            holds.current(),
        )
    }

    @Test
    fun `settle ends a hold that stayed clear even when no tick observes it, as the summary does`() {
        logged { logs ->
            hold()
            admit()
            after(AutomationLimits.HOLD_SETTLE)

            holds.settle(clock.now)

            assertEquals(listOf(Level.WARN, Level.INFO), logs.events.map { it.level })
            assertTrue(logs.messages(Level.INFO).single().contains("after 0s."), logs.messages(Level.INFO).single())
        }
    }

    @Test
    fun `after a restart nothing is remembered, so a hold still in force is logged again`() {
        logged { logs ->
            hold()
            AutomationHolds(clock).observe(AutomationHoldKind.REQUEST_CAP, 3, 0, atCap)

            assertEquals(2, logs.at(Level.WARN).size)
        }
    }
}
