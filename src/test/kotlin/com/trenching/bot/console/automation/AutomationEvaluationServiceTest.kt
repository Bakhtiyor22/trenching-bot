package com.trenching.bot.console.automation

import com.trenching.bot.common.Base58
import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationFixtures.WALLET
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.domain.AutomationEvaluationView
import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionDetail
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.automation.domain.EvaluationStatus
import com.trenching.bot.console.automation.domain.SessionCloseReason
import com.trenching.bot.console.automation.domain.SweepPlans
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunEntity
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.portfolio.domain.PositionState
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Plan Rules 22 and 24–26 and Acceptance Criterion 7: scoring closed sessions over their rolling window. */
class AutomationEvaluationServiceTest : AutomationDatabaseTest() {
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val clock = MutableClock(EVENING.plus(Duration.ofDays(30)))
    private val properties = properties(captureEnabled = true)
    private val plan = SweepPlans.DECU_SWEEP_1

    @Test
    fun `the window is the session and the six evaluated sessions before it by first buy, skipping a phase-0 session and other wallets`() {
        listOf(9, 3, 7, 1, 5, 8, 2, 6).forEach { day -> scoredSession(day, realized = "0.0$day") }
        sessions.save(AutomationRows.session("day-4", AutomationSessionState.CLOSED, firstBuy(4)))
        items.save(AutomationRows.item(400, "day-4", blockTime = firstBuy(4)))
        val otherFirstBuy = firstBuy(8).plus(Duration.ofHours(1))
        sessions.save(AutomationSessionEntity("other-wallet", OTHER_WALLET, "item-800", otherFirstBuy, otherFirstBuy, AutomationSessionState.CLOSED,
            null, null, 0, otherFirstBuy))
        simulatedItem(800, "other-wallet", mapOf("V00" to signalRun(realized = "5")))

        evaluationService(properties, clock).evaluateClosedSessions()

        assertEquals(listOf("day-2", "day-3", "day-5", "day-6", "day-7", "day-8", "day-9"), agreed("day-9").windowSessionIds)
        assertEquals(listOf("day-1", "day-2", "day-3", "day-5"), agreed("day-5").windowSessionIds)
        assertEquals(listOf("day-1"), agreed("day-1").windowSessionIds)
        assertEquals(7, evaluations.findAllBySessionIdAndPlanIdOrderByLabelAsc("day-9", plan.id).first().windowSessions)
        assertEquals(BigDecimal("0.400000000000"), agreed("day-9").realizedPnl, "days 2, 3 and 5 to 9")
        assertEquals(7, agreed("day-9").signals)
        assertEquals(0, evaluations.findAllBySessionIdAndPlanIdOrderByLabelAsc("day-4", plan.id).size, "phase 0 is never evaluated")
        assertEquals(listOf("other-wallet"), agreed("other-wallet").windowSessionIds)
        assertEquals(9 * plan.entries.size, evaluations.count().toInt())
    }

    @Test
    fun `ten signals with a worst case of exactly zero are no success, the smallest step above zero succeeds, nine signals are no result`() {
        sessions.save(AutomationRows.session("night", AutomationSessionState.CLOSED, firstBuy(1)))
        (1..10).forEach { n ->
            simulatedItem(n, "night", mapOf(
                "V00" to signalRun(worst = if (n <= 5) "0.1" else "-0.1"),
                "V01" to signalRun(worst = if (n == 1) "0.000000000001" else "0"),
                "V02" to if (n == 10) Run("SIGNAL_BELOW_MIN_SIZE", signal = false) else signalRun(worst = "1"),
            ))
        }

        val scored = evaluationService(properties, clock).evaluate("night").associateBy { it.label }

        assertEquals(10, scored.getValue("V00").signals)
        assertEquals(0, scored.getValue("V00").worstCasePnl.signum())
        assertEquals(EvaluationStatus.NO_SUCCESS, scored.getValue("V00").status)
        assertEquals(EvaluationStatus.SUCCESS, scored.getValue("V01").status)
        assertEquals(9, scored.getValue("V02").signals)
        assertEquals(0, scored.getValue("V02").unscored)
        assertEquals(EvaluationStatus.NO_RESULT, scored.getValue("V02").status)
        assertEquals(10, scored.getValue("V03").unscored, "a simulated item without a row for the entry is unscored")
        assertEquals(plan.entries.map { it.label }, scored.keys.toList())
    }

    @Test
    fun `every row's numbers can be rebuilt by hand from the window's sweep runs and item states`() {
        sessions.save(AutomationRows.session("earlier", AutomationSessionState.CLOSED, firstBuy(1)))
        sessions.save(AutomationRows.session("night", AutomationSessionState.CLOSED, firstBuy(2)))
        simulatedItem(1, "earlier", plan.entries.associate { it.label to signalRun(realized = "0.03") })
        evaluationService(properties, clock).evaluate("earlier")
        simulatedItem(2, "night", plan.entries.associate { it.label to signalRun(realized = "0", worst = "-0.10225", state = "OPEN") })
        simulatedItem(3, "night", plan.entries.mapIndexed { index, entry -> entry.label to MIXED[index % MIXED.size] }.toMap())
        items.save(AutomationRows.item(4, "night", AutomationItemState.FAILED).apply { step = AutomationStep.MARKET })
        items.save(AutomationRows.item(5, "night", AutomationItemState.ABANDONED).apply { step = AutomationStep.WALLET })
        items.save(AutomationRows.item(6, "night", AutomationItemState.SKIPPED_NOT_FIRST_BUY).apply { step = AutomationStep.QUALIFY })

        val scored = evaluationService(properties, clock).evaluate("night")

        assertEquals(plan.entries.size, scored.size)
        scored.forEach { row -> assertEquals(byHand(row), Rebuilt(row.signals, row.scored, row.unscored, row.realizedPnl, row.worstCasePnl), row.label) }
    }

    @Test
    fun `statuses rank success first, then no success, then no result`() {
        sessions.save(AutomationRows.session("night", AutomationSessionState.CLOSED, firstBuy(1)))
        (1..10).forEach { n -> simulatedItem(n, "night", mapOf("V00" to signalRun(worst = "-1"), "V01" to signalRun(worst = "1"))) }

        evaluationService(properties, clock).evaluateClosedSessions()

        val ranked = query(properties, clock).evaluations(null, null, null, null, null, "status", 0, 100).items
        assertEquals(listOf(EvaluationStatus.SUCCESS, EvaluationStatus.NO_SUCCESS) + List(8) { EvaluationStatus.NO_RESULT }, ranked.map { it.status })
        assertEquals(EvaluationStatus.SUCCESS, query(properties, clock).session("night").session.bestStatus)
        assertEquals("V01", query(properties, clock).session("night").session.bestLabel)
    }

    @Test
    fun `re-evaluating a session returns its stored rows and replaces nothing`() {
        sessions.save(AutomationRows.session("night", AutomationSessionState.CLOSED, firstBuy(1)))
        simulatedItem(1, "night", mapOf("V00" to signalRun(realized = "0.5")))
        val first = evaluationService(properties, clock).evaluate("night")
        simulatedItem(2, "night", mapOf("V00" to signalRun(realized = "9")))
        clock.now = clock.now.plus(Duration.ofHours(1))

        val again = evaluationService(properties, clock).evaluate("night")
        evaluationService(properties, clock).evaluateClosedSessions()

        assertEquals(first.sortedBy { it.label }, again.sortedBy { it.label })
        assertEquals(BigDecimal("0.500000000000"), again.single { it.label == "V00" }.realizedPnl)
        assertEquals(plan.entries.size, evaluations.count().toInt())
    }

    @Test
    fun `nothing is evaluated with capture off, nor a session that is not closed`() {
        sessions.save(AutomationRows.session("closed", AutomationSessionState.CLOSED, firstBuy(1)))
        sessions.save(AutomationRows.session("closing", AutomationSessionState.CLOSING, firstBuy(2)))
        simulatedItem(1, "closed", mapOf("V00" to signalRun()))
        simulatedItem(2, "closing", mapOf("V00" to signalRun()))

        evaluationService(properties(captureEnabled = false), clock).evaluateClosedSessions()
        val off = evaluations.count()
        evaluationService(properties, clock).evaluateClosedSessions()

        assertEquals(0, off)
        assertEquals(setOf("closed"), evaluations.findAll().map { it.sessionId }.toSet())
    }

    @Test
    fun `a closing session waits while an item is captured and closes once it is simulated, then is evaluated`() {
        sessions.save(AutomationRows.session("night", AutomationSessionState.CLOSING, firstBuy(1)))
        items.save(AutomationRows.item(1, "night", AutomationItemState.CAPTURED, blockTime = firstBuy(1)).apply { step = AutomationStep.REGISTER })

        sessionService(properties, clock).closeIdleSessions()
        val waiting = sessions.findOneById("night")?.state
        items.findOneById("item-1")?.let { item ->
            item.state = AutomationItemState.SIMULATED
            item.step = AutomationStep.SWEEP
            val simulated = items.save(item)
            sweepRuns.saveAll(plan.entries.map { signalRun().entity(simulated, it.label) })
        }
        sessionService(properties, clock).closeIdleSessions()
        evaluationService(properties, clock).evaluateClosedSessions()

        assertEquals(AutomationSessionState.CLOSING, waiting)
        assertEquals(AutomationSessionState.CLOSED, sessions.findOneById("night")?.state)
        assertEquals(plan.entries.size, evaluations.findAllBySessionIdAndPlanIdOrderByLabelAsc("night", plan.id).size)
    }

    @Test
    fun `closing a session by hand abandons its captured items and evaluates it at once`() {
        sessions.save(AutomationRows.session("night", AutomationSessionState.OPEN, firstBuy(1)))
        simulatedItem(1, "night", plan.entries.associate { it.label to signalRun(realized = "0.02") })
        items.save(AutomationRows.item(2, "night", AutomationItemState.CAPTURED, blockTime = firstBuy(1)).apply { step = AutomationStep.REGISTER })

        val detail = sessionService(properties, clock).close("night", "close-1")

        assertEquals(AutomationSessionState.CLOSED, detail.session.session.state)
        assertEquals(SessionCloseReason.MANUAL, detail.session.session.closeReason)
        assertEquals(AutomationItemState.ABANDONED, items.findOneById("item-2")?.state)
        assertEquals(plan.entries.map { it.label }, detail.evaluations.map { it.label })
        assertTrue(detail.evaluations.all { it.signals == 1 && it.unscored == 1 })
        assertEquals(BigDecimal("0.020000000000"), detail.evaluations.first().realizedPnl)
    }

    @Test
    fun `with capture off a session closed by hand is not evaluated`() {
        sessions.save(AutomationRows.session("night", AutomationSessionState.OPEN, firstBuy(1)))
        simulatedItem(1, "night", mapOf("V00" to signalRun()))

        val detail = sessionService(properties(captureEnabled = false), clock).close("night", "close-1")

        assertEquals(AutomationSessionState.CLOSED, detail.session.session.state)
        assertEquals(0, detail.evaluations.size)
        assertEquals(0, evaluations.count())
    }

    @Test
    fun `every row's scored and unscored items add up to the window's counted items, never overlapping`() {
        sessions.save(AutomationRows.session("night", AutomationSessionState.CLOSED, firstBuy(1)))
        MIXED.forEachIndexed { index, run -> simulatedItem(index + 1, "night", plan.entries.associate { it.label to run }) }
        items.save(AutomationRows.item(20, "night", AutomationItemState.FAILED).apply { step = AutomationStep.MARKET })

        val agreed = evaluationService(properties, clock).evaluate("night").single { it.label == "V00" }

        assertEquals(listOf(3, 4), listOf(agreed.scored, agreed.unscored), "NO_DIP, below size and stop loss are scored")
        assertEquals(MIXED.size + 1, agreed.scored + agreed.unscored)
    }

    @Test
    fun `a session is evaluated only once every earlier session of its wallet is closed`() {
        sessions.save(AutomationRows.session("earlier", AutomationSessionState.CLOSING, firstBuy(1)))
        sessions.save(AutomationRows.session("night", AutomationSessionState.CLOSED, firstBuy(2)))
        simulatedItem(1, "earlier", mapOf("V00" to signalRun(realized = "0.01")))
        simulatedItem(2, "night", mapOf("V00" to signalRun(realized = "0.02")))

        evaluationService(properties, clock).evaluateClosedSessions()
        val whileEarlierOpen = evaluations.count()
        sessions.findOneById("earlier")?.let { it.state = AutomationSessionState.CLOSED; sessions.save(it) }
        evaluationService(properties, clock).evaluateClosedSessions()

        assertEquals(0, whileEarlierOpen)
        assertEquals(listOf("earlier", "night"), agreed("night").windowSessionIds)
        assertEquals(BigDecimal("0.030000000000"), agreed("night").realizedPnl)
    }

    @Test
    fun `a manual close leaves the evaluation to a later tick while an earlier session of the wallet is still open`() {
        sessions.save(AutomationRows.session("earlier", AutomationSessionState.OPEN, firstBuy(1)))
        sessions.save(AutomationRows.session("night", AutomationSessionState.OPEN, firstBuy(2)))
        simulatedItem(1, "earlier", mapOf("V00" to signalRun(realized = "0.01")))
        simulatedItem(2, "night", mapOf("V00" to signalRun(realized = "0.02")))

        val detail = sessionService(properties, clock).close("night", "close-1")
        sessionService(properties, clock).close("earlier", "close-2")
        evaluationService(properties, clock).evaluateClosedSessions()

        assertEquals(AutomationSessionState.CLOSED, detail.session.session.state)
        assertEquals(0, detail.evaluations.size)
        assertEquals(listOf("earlier", "night"), agreed("night").windowSessionIds)
    }

    @Test
    fun `after a plan switch only sessions swept under the active plan are scored, and the window starts afresh`() {
        sessions.save(AutomationRows.session("old-plan", AutomationSessionState.CLOSED, firstBuy(1)))
        sessions.save(AutomationRows.session("new-plan", AutomationSessionState.CLOSED, firstBuy(2)))
        simulatedItem(1, "old-plan", mapOf("V00" to signalRun(realized = "5")), planId = "decu-sweep-0")
        simulatedItem(2, "new-plan", mapOf("V00" to signalRun(realized = "0.02")))

        evaluationService(properties, clock).evaluateClosedSessions()

        assertEquals(setOf("new-plan"), evaluations.findAll().map { it.sessionId }.toSet())
        assertEquals(listOf("new-plan"), agreed("new-plan").windowSessionIds)
        assertEquals(BigDecimal("0.020000000000"), agreed("new-plan").realizedPnl)
    }

    @Test
    fun `a manual close scoring a session the tick is scoring keeps the tick's rows and replaces nothing`() {
        sessions.save(AutomationRows.session("night", AutomationSessionState.CLOSED, firstBuy(1)))
        simulatedItem(1, "night", plan.entries.associate { it.label to signalRun(realized = "0.02") })
        val manual = sessionService(properties, MutableClock(clock.now.plus(Duration.ofHours(1))))
        val manualClose = Executors.newSingleThreadExecutor()
        try {
            val (tickRows, closed) = requireNotNull(TransactionTemplate(transactions).execute {
                val rows = evaluationTransaction.evaluate("night", plan, properties.evaluationWindowSessions, properties.evaluationRule(), clock.instant())
                val pending = manualClose.submit<AutomationSessionDetail> { manual.close("night", "close-1") }
                awaitBlockedTransaction()
                rows to pending
            })

            val detail = closed.get(30, TimeUnit.SECONDS)

            assertEquals(tickRows, detail.evaluations)
            assertTrue(detail.evaluations.all { it.evaluatedAt == clock.now })
            assertEquals(plan.entries.size, evaluations.count().toInt())
        } finally {
            manualClose.shutdownNow()
        }
    }

    /** Waits until another transaction waits on a lock, here the second set of evaluation rows on the first one's keys. */
    private fun awaitBlockedTransaction() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (jdbc.queryForObject(LOCK_WAITS, Long::class.java) == 0L) {
            check(System.nanoTime() < deadline) { "The second evaluation never waited for the first" }
            Thread.sleep(20)
        }
    }

    private fun firstBuy(day: Int) = EVENING.plus(Duration.ofDays(day.toLong()))

    private fun scoredSession(day: Int, realized: String) {
        sessions.save(AutomationRows.session("day-$day", AutomationSessionState.CLOSED, firstBuy(day)))
        simulatedItem(day, "day-$day", mapOf("V00" to signalRun(realized = realized)))
    }

    private fun agreed(sessionId: String): AutomationEvaluationView =
        records.toEvaluation(evaluations.findAllBySessionIdAndPlanIdOrderByLabelAsc(sessionId, plan.id).single { it.label == "V00" })

    private fun simulatedItem(n: Int, sessionId: String, runs: Map<String, Run>, planId: String = PLAN_ID) {
        val item = items.save(AutomationRows.item(n, sessionId, AutomationItemState.SIMULATED, blockTime = EVENING).apply { step = AutomationStep.SWEEP })
        sweepRuns.saveAll(runs.map { (label, run) -> run.entity(item, label, planId) })
    }

    /** Plan Rules 22 and 24 (clarified 2026-11-03) written out over the stored rows of the session's window. */
    private fun byHand(row: AutomationEvaluationView): Rebuilt {
        val windowItems = items.findAll().filter { it.sessionId in row.windowSessionIds }
        val simulated = windowItems.filter { it.state == AutomationItemState.SIMULATED }.map { it.id }.toSet()
        val runs = sweepRuns.findAll().filter { it.itemId in simulated && it.label == row.label && it.planId == plan.id }
        val signals = runs.filter { it.isSignal }
        val (unusable, usable) = runs.partition { it.runId == null || it.outcome in setOf("REFERENCE_UNAVAILABLE", "SIGNAL_SIZE_UNAVAILABLE", "NO_CYCLE") }
        val unscored = windowItems.count { it.state == AutomationItemState.FAILED || it.state == AutomationItemState.ABANDONED } +
            unusable.size + simulated.count { id -> runs.none { it.itemId == id } }
        return Rebuilt(signals.size, usable.size, unscored,
            signals.fold(BigDecimal.ZERO) { sum, run -> sum.add(run.realizedPnl) }.setScale(12),
            signals.fold(BigDecimal.ZERO) { sum, run -> sum.add(run.worstCasePnl) }.setScale(12))
    }

    private data class Rebuilt(val signals: Int, val scored: Int, val unscored: Int, val realized: BigDecimal, val worst: BigDecimal)

    private data class Run(val outcome: String, val signal: Boolean, val realized: String = "0", val worst: String = realized, val refused: Boolean = false, val state: String? = null) {
        fun entity(item: AutomationItemEntity, label: String, planId: String = PLAN_ID): AutomationSweepRunEntity {
            val runId = if (refused) null else "run-${item.id}-$label"
            return AutomationSweepRunEntity(AutomationIds.sweepRun(item.id, planId, label), item.id, item.sessionId, planId, label, runId,
                runId?.let { "hash" }, runId?.let { "dip-paper-v6" }, outcome, state?.let(PositionState::valueOf),
                signal, BigDecimal(realized).setScale(12), BigDecimal(worst).setScale(12), EVENING)
        }
    }

    private companion object {
        const val PLAN_ID = "decu-sweep-1"
        const val LOCK_WAITS = "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()"
        val OTHER_WALLET: String = Base58.encode(ByteArray(32) { 7 })

        fun signalRun(realized: String = "0", worst: String = realized, state: String? = null) =
            Run(if (state == "OPEN") "HORIZON_REACHED" else "TAKE_PROFIT", signal = true, realized, worst, state = state)

        val MIXED: List<Run> = listOf(
            Run("NO_DIP", signal = true),
            Run("SIGNAL_BELOW_MIN_SIZE", signal = false),
            Run("REFERENCE_UNAVAILABLE", signal = false),
            Run("MARKET_COVERAGE_ENDED", signal = false, refused = true),
            Run("STOP_LOSS", signal = true, realized = "-0.04"),
            Run("NO_CYCLE", signal = false),
        )
    }
}
