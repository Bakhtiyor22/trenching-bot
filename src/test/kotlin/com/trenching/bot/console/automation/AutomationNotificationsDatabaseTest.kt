package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationFixtures.WALLET
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.AutomationRows.item
import com.trenching.bot.console.automation.AutomationRows.session
import com.trenching.bot.console.automation.application.AutomationDayVerdictCheck
import com.trenching.bot.console.automation.domain.AutomationItemProgress
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.automation.domain.IllegalItemTransitionException
import com.trenching.bot.console.automation.domain.simulated
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkEntity
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.notification.NotificationKind
import com.trenching.bot.notification.application.NotificationOutboxStore
import com.trenching.bot.notification.application.OutboxNotificationPublisher
import com.trenching.bot.notification.infrastructure.persistence.NotificationOutboxRepository
import com.trenching.bot.portfolio.domain.PositionState
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Notification plan Acceptance Criteria 2, 3, 4 and 6 against PostgreSQL: a buy that becomes `SIMULATED` publishes its result in the
 * transaction that stores the state, and the hourly check publishes each decided day once. The recording publisher stands in for the
 * outbox except where a test needs the real one.
 */
class AutomationNotificationsDatabaseTest : AutomationDatabaseTest() {
    @Autowired private lateinit var outboxStore: NotificationOutboxStore
    @Autowired private lateinit var outbox: NotificationOutboxRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val clock = MutableClock(EVENING.plus(Duration.ofHours(1)))
    private val dayClock = MutableClock(AutomationDaySeed.local("2026-11-12", "13:00"))
    private val seed by lazy { AutomationDaySeed(sessions, items, sweepRuns) }

    @BeforeEach
    fun emptyOutbox() = outbox.deleteAllInBatch()

    @Test
    fun `a live buy that becomes simulated with one variation in profit publishes one result with its links, and a repeat publishes nothing`() {
        capturedBuy(1)
        sweepRow(1, "V00", "TAKE_PROFIT", "0.012345678901", PositionState.CLOSED)
        sweepRow(1, "V01", "NO_DIP", "0", null)

        assertTrue(simulate(1))
        assertFalse(simulate(1), "the buy already moved on")

        val published = notifications.recording.notifications.single()
        assertEquals("BUY_RESULT|item-1", published.key)
        assertEquals(NotificationKind.BUY_RESULT, published.kind)
        assertEquals(listOf("V00 profit +0.0123 SOL, take profit", "No trade: V01 no dip"), published.lines)
        assertEquals(listOf("/automation/days/2026-11-02?item=item-1", "/runs/run-V00", "/runs/run-V01"), published.links.map { it.path })
        assertEquals(AutomationItemState.SIMULATED, items.findOneById("item-1")?.state)
    }

    @Test
    fun `a buy with no closed profit or loss, and a backfilled buy, publish nothing`() {
        capturedBuy(1)
        sweepRow(1, "V00", "HORIZON_REACHED", "0", PositionState.OPEN)
        sweepRow(1, "V01", "TIME_EXIT", "0", PositionState.CLOSED)
        capturedBuy(2, backfillId = "backfill-1")
        sweepRow(2, "V00", "TAKE_PROFIT", "0.01", PositionState.CLOSED)

        assertTrue(simulate(1))
        assertTrue(simulate(2))

        assertEquals(emptyList(), notifications.recording.notifications)
        assertEquals(listOf(AutomationItemState.SIMULATED, AutomationItemState.SIMULATED), listOf("item-1", "item-2").map { items.findOneById(it)?.state })
    }

    @Test
    fun `a transition that fails or is refused publishes nothing`() {
        capturedBuy(1)
        sweepRow(1, "V00", "TAKE_PROFIT", "0.01", PositionState.CLOSED)
        val before = progress(1)

        assertFalse(pipelineTransaction.record("item-1", before.copy(stepAttempt = 9), before.simulated(), null, clock.instant()), "the buy is not where the caller read it")
        assertFailsWith<IllegalItemTransitionException> {
            pipelineTransaction.record("item-1", before, before.copy(state = AutomationItemState.DISCOVERED), null, clock.instant())
        }

        assertEquals(emptyList(), notifications.recording.notifications)
        assertEquals(AutomationItemState.CAPTURED, items.findOneById("item-1")?.state)
    }

    @Test
    fun `with the real outbox the row is written in the transition's transaction and is absent after a rollback`() {
        notifications.use(OutboxNotificationPublisher(outboxStore, clock, NotificationKind.entries.toSet()))
        capturedBuy(1)
        sweepRow(1, "V00", "TAKE_PROFIT", "0.01", PositionState.CLOSED)
        val before = progress(1)
        val transactions = TransactionTemplate(transactionManager)

        runCatching {
            transactions.execute {
                pipelineTransaction.record("item-1", before, before.simulated(), null, clock.instant())
                assertEquals(listOf("BUY_RESULT|item-1"), outbox.findAll().map { it.notificationKey }, "visible inside the transaction")
                error("rolled back after the state change")
            }
        }

        assertEquals(emptyList(), outbox.findAll().map { it.notificationKey })
        assertEquals(AutomationItemState.CAPTURED, items.findOneById("item-1")?.state)

        assertTrue(simulate(1))
        assertFalse(simulate(1))

        assertEquals(listOf("BUY_RESULT|item-1"), outbox.findAll().map { it.notificationKey })
        assertEquals(AutomationItemState.SIMULATED, items.findOneById("item-1")?.state)
    }

    @Test
    fun `the hourly check publishes each decided day once, in the outbox, and a second check adds nothing`() {
        notifications.use(OutboxNotificationPublisher(outboxStore, clock, NotificationKind.entries.toSet()))
        seed.week()
        coverage(from = AutomationDaySeed.local("2026-10-20", "12:00"))

        check().publishDecidedDays()
        val first = outbox.findAll().map { it.notificationKey }.sorted()
        check().publishDecidedDays()

        assertEquals((6..11).map { "DAY_VERDICT|decu-sweep-1|2026-11-" + it.toString().padStart(2, '0') }, first, "today's day is not covered yet, so it is pending")
        assertEquals(first, outbox.findAll().map { it.notificationKey }.sorted())
    }

    @Test
    fun `a day with a pending row is never published, and a day's verdict that changes later is not sent again`() {
        notifications.use(OutboxNotificationPublisher(outboxStore, clock, NotificationKind.entries.toSet()))
        seed.week()
        sessions.findOneById("session-2026-11-08")?.let { sessions.save(it.apply { state = AutomationSessionState.OPEN }) }
        coverage(from = AutomationDaySeed.local("2026-10-20", "12:00"))

        check().publishDecidedDays()

        assertEquals(listOf("DAY_VERDICT|decu-sweep-1|2026-11-06", "DAY_VERDICT|decu-sweep-1|2026-11-07"), outbox.findAll().map { it.notificationKey }.sorted())
        val stored = outbox.findAll().associate { it.notificationKey to it.payload }

        sweepRuns.deleteAll(sweepRuns.findAll().filter { it.itemId == "item-1" })
        check().publishDecidedDays()

        assertEquals(stored, outbox.findAll().associate { it.notificationKey to it.payload })
    }

    @Test
    fun `days decided in one check are published oldest first`() {
        seed.week()
        coverage(from = AutomationDaySeed.local("2026-10-20", "12:00"))

        check().publishDecidedDays()

        val keys = notifications.recording.notifications.map { it.key }
        assertEquals((6..11).map { "DAY_VERDICT|decu-sweep-1|2026-11-" + it.toString().padStart(2, '0') }, keys)
    }

    @Test
    fun `a buy's lines follow the plan's order, with an archived variation in its place, an unknown label last and another plan's rows left out`() {
        capturedBuy(1)
        val replacement = AutomationVariationRows(variationRows, variationMapper).edit("V01", EVENING, "request-1").label
        sweepRow(1, "X01", "STOP_LOSS", "-0.04", PositionState.CLOSED)
        sweepRow(1, replacement, "TAKE_PROFIT", "0.03", PositionState.CLOSED)
        sweepRow(1, "V01", "STOP_LOSS", "-0.02", PositionState.CLOSED)
        sweepRow(1, "V00", "TAKE_PROFIT", "0.01", PositionState.CLOSED)
        sweepRow(1, "V02", "TAKE_PROFIT", "0.05", PositionState.CLOSED, planId = "another-plan")

        assertTrue(simulate(1))

        val published = notifications.recording.notifications.single()
        val expected = listOf("V00", "V01", replacement, "X01")
        assertEquals(expected, published.lines.map { it.substringBefore(' ') })
        assertTrue(published.title.endsWith("at 2 Nov 20:00: 2 profit, 2 loss"), published.title)
        assertEquals(expected.map { "/runs/run-$it" }, published.links.drop(1).map { it.path })
    }

    @Test
    fun `a check that fails is logged and left for the next hour`() {
        val failing = AutomationDayVerdictCheck(dayService(properties(captureEnabled = true), dayClock), { error("outbox is down") }, properties(captureEnabled = true), dayClock)
        seed.week()
        coverage(from = AutomationDaySeed.local("2026-10-20", "12:00"))

        LogCapture("com.trenching.bot", ch.qos.logback.classic.Level.ERROR).use { logs ->
            failing.publishDecidedDays()

            assertEquals(1, logs.events.size)
        }
    }

    private fun check() = AutomationDayVerdictCheck(dayService(properties(captureEnabled = true), dayClock), notifications.publisher, properties(captureEnabled = true), dayClock)

    private fun coverage(from: Instant) {
        watermarks.save(AutomationWatermarkEntity(WALLET, 1, "sig-1", 2, -1, dayClock.instant()).apply { this.coveredFrom = from })
    }

    private fun capturedBuy(n: Int, backfillId: String? = null) {
        if (!sessions.existsById("session-1")) sessions.save(session("session-1", AutomationSessionState.OPEN, EVENING))
        items.save(item(n, "session-1", AutomationItemState.CAPTURED, blockTime = EVENING.plusSeconds(n.toLong())).apply {
            step = AutomationStep.REGISTER
            stepAttempt = 1
            this.backfillId = backfillId
        })
    }

    private fun progress(n: Int): AutomationItemProgress = records.toStanding(checkNotNull(items.findOneById("item-$n"))).progress

    private fun simulate(n: Int): Boolean {
        val before = progress(n).let { if (items.findOneById("item-$n")?.state == AutomationItemState.SIMULATED) it.copy(state = AutomationItemState.CAPTURED) else it }
        return pipelineTransaction.record("item-$n", before, before.simulated(), null, clock.instant())
    }

    private fun sweepRow(n: Int, label: String, outcome: String, pnl: String, state: PositionState?, planId: String = "decu-sweep-1") {
        sweepRuns.save(AutomationSweepRunEntity(
            "row-$n-$planId-$label", "item-$n", "session-1", planId, label, "run-$label", "hash", "dip-paper-v6", outcome, state, true,
            BigDecimal(pnl), BigDecimal(pnl), EVENING,
        ))
    }
}
