package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationFixtures.buy
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.AutomationFixtures.signature
import com.trenching.bot.console.automation.application.AutomationCaptureCancellation
import com.trenching.bot.console.automation.api.AutomationHistoryStartRunner
import com.trenching.bot.console.automation.application.AutomationHistoryStartService
import com.trenching.bot.console.automation.application.AutomationHistoryStartTransaction
import com.trenching.bot.console.automation.application.AutomationHolds
import com.trenching.bot.console.automation.application.AutomationPipelineService
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationItemChange
import com.trenching.bot.console.automation.domain.AutomationHistoryStartResult
import com.trenching.bot.console.automation.domain.AutomationItemEventDraft
import com.trenching.bot.console.automation.domain.AutomationItemEventKind
import com.trenching.bot.console.automation.domain.AutomationItemEventView
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.automation.domain.SweepEntryResult
import com.trenching.bot.console.automation.domain.simulated
import com.trenching.bot.console.automation.domain.started
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.mapper.AutomationCaptureRequestMapperImpl
import com.trenching.bot.console.capture.CaptureNames
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.replay.CaptureStepCommand
import com.trenching.bot.replay.api.RunSearchIndexBackfillRunner
import com.trenching.bot.walletactivity.WalletAction
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.annotation.OrderUtils
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The token flow history (plan 20261116, Domain Rules H1-H5): every change to an item is an append-only event written in the
 * change's transaction, over the real pipeline, capture services and a scripted workflow.
 */
class AutomationItemEventsTest : AutomationDatabaseTest() {
    @TempDir lateinit var root: Path
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val clock = MutableClock(EVENING)
    private val holds = AutomationHolds(clock)
    private val usage = FakeApiUsageReports()
    private val lookup = FakeWalletActivityLookup()
    private val properties = properties(captureEnabled = true)
    private lateinit var capture: CaptureHarness

    @BeforeEach
    fun startCapture() {
        capture = CaptureHarness(root, clock)
    }

    @AfterEach
    fun stopCapture() {
        capture.close()
    }

    @Test
    fun `discovering a buy records it as found, with no state before it`() {
        discover(buy(1, 100, EVENING))

        val found = events(item(1)).single()

        assertEquals(1, found.seq)
        assertEquals(AutomationItemEventKind.STATE_CHANGED, found.kind)
        assertNull(found.fromState)
        assertEquals(AutomationItemState.DISCOVERED, found.toState)
        assertEquals(EVENING, found.at)
    }

    @Test
    fun `a buy without a block time is found already failed, with its code`() {
        discover(buy(1, 100, null))

        val found = events(item(1)).single()

        assertEquals(AutomationItemState.FAILED, found.toState)
        assertEquals(AutomationFailures.MISSING_BLOCK_TIME, found.failureCode)
    }

    @Test
    fun `a finished capture shows every step with its start, end, attempt, operation and requests`() {
        discover(buy(1, 100, EVENING))
        usage.byCapture[captureSessionId(1)] = 12

        runCapture()

        val item = item(1)
        val history = events(item)
        assertEquals(AutomationItemState.CAPTURED, item.state)
        assertEquals((1..history.size).toList(), history.map { it.seq }, "gapless from 1")
        val started = history.filter { it.kind == AutomationItemEventKind.OPERATION_STARTED }
        val ended = history.filter { it.kind == AutomationItemEventKind.OPERATION_ENDED }
        assertEquals(listOf(AutomationStep.WALLET, AutomationStep.MARKET, AutomationStep.RATES, AutomationStep.EXPORT, AutomationStep.QUALIFY), started.map { it.step })
        assertEquals(started.map { it.operationId }, ended.map { it.operationId }, "each operation that started also ended, in order")
        assertTrue(started.all { it.attempt == 1 && it.captureSessionId == item.captureSessionId })
        assertTrue(ended.all { it.detail?.get("operationState") == CaptureNames.SUCCEEDED })
        assertEquals(12, ended.sumOf { it.requestsUsed ?: 0 }, "each operation's own requests add up to the capture's total")
        assertEquals(12, item.requestsUsed)
        history.zipWithNext().forEach { (before, after) ->
            assertTrue(!after.at.isBefore(before.at), "events are in time order")
            if (after.kind == AutomationItemEventKind.STATE_CHANGED && before.toState != null && after.fromState != null && before.kind == AutomationItemEventKind.STATE_CHANGED) {
                assertEquals(before.toState, after.fromState, "each state change starts where the one before ended")
            }
        }
        val states = history.filter { it.kind == AutomationItemEventKind.STATE_CHANGED }
        assertNull(states.first().fromState)
        assertEquals(AutomationItemState.CAPTURED, states.last().toState)
    }

    @Test
    fun `a retried buy keeps its failure as its own events and shows the retry apart from it`() {
        capture.workflow.fail(CaptureStepCommand.Rates::class, "RATES_INCOMPLETE", times = 2)
        discover(buy(1, 100, EVENING))
        runCapture()
        val failed = item(1)
        assertEquals(AutomationItemState.FAILED, failed.state)

        retryService(properties, clock).retry(failed.id, "retry-1")
        runCapture()

        val history = events(item(1))
        val attempts = history.filter { it.kind == AutomationItemEventKind.ATTEMPT_FAILED }
        assertEquals(listOf(1, 2), attempts.map { it.attempt })
        assertTrue(attempts.all { it.step == AutomationStep.RATES && it.failureCode == "RATES_INCOMPLETE" })
        val toFailed = history.single { it.kind == AutomationItemEventKind.STATE_CHANGED && it.toState == AutomationItemState.FAILED }
        assertTrue(toFailed.seq > attempts.last().seq)
        val retry = history.single { it.kind == AutomationItemEventKind.RETRY_REQUESTED }
        assertEquals("retry-1", retry.requestId)
        assertEquals(AutomationItemState.FAILED, retry.fromState)
        assertEquals(AutomationStep.RATES, retry.step)
        assertTrue(retry.seq > toFailed.seq, "the retry comes after the failure it answers")
        assertEquals(AutomationItemState.CAPTURED, item(1).state)
        assertNull(item(1).failureCode, "the item's own failure is cleared")
        assertTrue(history.filter { it.failureCode == "RATES_INCOMPLETE" }.size >= 2, "the earlier failure stays in the history")
        assertEquals((1..history.size).toList(), history.map { it.seq })
    }

    @Test
    fun `a repeated retry request adds no event`() {
        capture.workflow.fail(CaptureStepCommand.Rates::class, "RATES_INCOMPLETE", times = 2)
        discover(buy(1, 100, EVENING))
        runCapture()
        val id = item(1).id
        retryService(properties, clock).retry(id, "retry-1")
        val afterFirst = itemEvents.findAllByItemIdOrderBySeqAsc(id, PAGE).size

        retryService(properties, clock).retry(id, "retry-1")

        assertEquals(afterFirst, itemEvents.findAllByItemIdOrderBySeqAsc(id, PAGE).size)
    }

    @Test
    fun `a change that rolls back leaves no event`() {
        val seeded = seededCapturing()
        val before = records.toProgress(seeded)
        val template = TransactionTemplate(transactionManager)

        runCatching {
            template.execute {
                pipelineTransaction.record(seeded.id, before, before.started("op-1"), null, EVENING)
                error("rolled back")
            }
        }

        assertEquals(emptyList(), itemEvents.findAllByItemIdOrderBySeqAsc(seeded.id, PAGE))
        assertNull(items.findOneById(seeded.id)?.operationId)
    }

    @Test
    fun `two writers of one item never leave a gap or a repeat, and the one that loses fails with the conflict its callers handle`() {
        val seeded = seededCapturing()
        historyStart()
        val before = records.toProgress(seeded)
        val template = TransactionTemplate(transactionManager)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Result<Unit>> {
                runCatching {
                    template.execute {
                        pipelineTransaction.record(seeded.id, before, before.started("op-first"), null, EVENING)
                        holding.countDown()
                        check(release.await(30, TimeUnit.SECONDS)) { "the other writer never finished" }
                    }
                }
            }
            assertTrue(holding.await(30, TimeUnit.SECONDS))
            val second = executor.submit<Result<Unit>> {
                runCatching { template.execute { pipelineTransaction.record(seeded.id, before, before.started("op-second"), null, EVENING) }; Unit }
            }
            awaitLockWaiterOr { second.isDone }
            release.countDown()

            val results = listOf(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))

            val winners = results.filter { it.isSuccess }
            val losers = results.filter { it.isFailure }
            assertEquals(1, winners.size, "exactly one writer commits")
            assertTrue(losers.single().exceptionOrNull() is OptimisticLockingFailureException, "the loser fails the way the callers expect: ${losers.single().exceptionOrNull()}")
        } finally {
            executor.shutdownNow()
        }
        val history = itemEvents.findAllByItemIdOrderBySeqAsc(seeded.id, PAGE)
        assertEquals(listOf(1, 2), history.map { it.seq })
        assertEquals(AutomationItemEventKind.OPERATION_STARTED, history.last().kind)
        assertEquals(items.findOneById(seeded.id)?.operationId, history.last().operationId)
    }

    @Test
    fun `two sweep entries of one item recorded at once both keep their event, one after the other`() {
        val captured = seededCaptured()
        val due = records.toDueItem(captured)
        val template = TransactionTemplate(transactionManager)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Boolean> {
                template.execute {
                    val recorded = sweepTransaction.record(due, "decu-sweep-1", "V00", SweepEntryResult.refused("MARKET_COVERAGE_ENDED"), EVENING)
                    holding.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "the other writer never finished" }
                    recorded
                } == true
            }
            assertTrue(holding.await(30, TimeUnit.SECONDS))
            val second = executor.submit<Boolean> { sweepTransaction.record(due, "decu-sweep-1", "V01", SweepEntryResult.refused("MARKET_COVERAGE_ENDED"), EVENING) }
            awaitLockWaiterOr { second.isDone }
            release.countDown()

            assertTrue(first.get(30, TimeUnit.SECONDS))
            assertTrue(second.get(30, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
        val history = events(captured)
        assertEquals(listOf(1, 2, 3), history.map { it.seq })
        assertEquals(listOf(AutomationItemEventKind.HISTORY_STARTED, AutomationItemEventKind.SWEEP_ENTRY_RECORDED, AutomationItemEventKind.SWEEP_ENTRY_RECORDED), history.map { it.kind })
        assertEquals(setOf("V00", "V01"), history.drop(1).map { it.detail?.get("label") }.toSet())
    }

    @Test
    fun `a manual close writes one event for each item it abandons, under the close's request id`() {
        discover(buy(1, 100, EVENING), buy(2, 101, EVENING.plusSeconds(60)))
        val sessionId = item(1).sessionId

        sessionService(properties, clock).close(sessionId, "close-1")

        listOf(1, 2).forEach { n ->
            val history = events(item(n))
            assertEquals(2, history.size, "found, then abandoned")
            with(history.last()) {
                assertEquals(AutomationItemEventKind.STATE_CHANGED, kind)
                assertEquals(AutomationItemState.DISCOVERED, fromState)
                assertEquals(AutomationItemState.ABANDONED, toState)
                assertEquals("close-1", requestId)
            }
        }
    }

    @Test
    fun `a sweep entry records its plan, label, run and outcome once, and a repeat adds nothing`() {
        val captured = seededCaptured()
        val due = records.toDueItem(captured)
        historyStart()

        val first = sweepTransaction.record(due, "decu-sweep-1", "V00", SweepEntryResult.refused("MARKET_COVERAGE_ENDED"), EVENING)
        val repeat = sweepTransaction.record(due, "decu-sweep-1", "V00", SweepEntryResult.refused("MARKET_COVERAGE_ENDED"), EVENING)

        assertTrue(first)
        assertEquals(false, repeat)
        val entry = events(captured).single { it.kind == AutomationItemEventKind.SWEEP_ENTRY_RECORDED }
        assertEquals(mapOf("planId" to "decu-sweep-1", "label" to "V00", "outcome" to "MARKET_COVERAGE_ENDED"), entry.detail)
        assertEquals(AutomationStep.SWEEP, entry.step)
        assertEquals(2, events(captured).size, "the history start and the one entry")
    }

    @Test
    fun `the end of a sweep records the item's move to simulated`() {
        val captured = seededCaptured()
        val before = records.toProgress(captured)

        pipelineTransaction.record(captured.id, before, before.simulated(), null, EVENING)

        val history = events(captured)
        assertEquals(listOf(AutomationItemEventKind.HISTORY_STARTED, AutomationItemEventKind.STATE_CHANGED), history.map { it.kind }, "the item had no events, so its history starts first")
        assertEquals(AutomationItemState.CAPTURED, history.first().toState)
        val moved = history.last()
        assertEquals(AutomationItemEventKind.STATE_CHANGED, moved.kind)
        assertEquals(AutomationItemState.CAPTURED, moved.fromState)
        assertEquals(AutomationItemState.SIMULATED, moved.toState)
        assertEquals(AutomationStep.SWEEP, moved.step)
    }

    @Test
    fun `an event keeps at most 512 characters of a failure message and its detail is stored as jsonb`() {
        val seeded = seededCapturing()
        val long = "x".repeat(900)
        val template = TransactionTemplate(transactionManager)

        template.execute {
            itemEventWriter.append(seeded.id, EVENING, AutomationItemChange.found(listOf(
                AutomationItemEventDraft(AutomationItemEventKind.ATTEMPT_FAILED, failureCode = "BOOM", failureMessage = long),
                AutomationItemEventDraft(AutomationItemEventKind.OPERATION_ENDED, detail = mapOf("operationState" to "FAILED")),
            )))
        }

        val stored = events(seeded)
        assertEquals(512, stored.first().failureMessage?.length)
        assertEquals(mapOf("operationState" to "FAILED"), stored.last().detail)
        val type = jdbc.queryForObject("select data_type from information_schema.columns where table_name = 'automation_item_events' and column_name = 'detail'", String::class.java)
        assertEquals("jsonb", type)
    }

    @Test
    fun `starting history gives every item without events exactly one HISTORY_STARTED with its state now, and a rerun adds nothing`() {
        discover(buy(1, 100, EVENING))
        val found = item(1)
        val failed = items.save(AutomationRows.item(2, "session-2", AutomationItemState.FAILED).apply {
            step = AutomationStep.RATES
            stepAttempt = 2
            captureSessionId = "capture-2"
            operationId = "op-2"
            failureCode = "RATES_INCOMPLETE"
            failureMessage = "rates were incomplete"
            requestsUsed = 31
        })
        val waiting = items.save(AutomationRows.item(3, "session-3", AutomationItemState.DISCOVERED))
        clock.now = EVENING.plusSeconds(3600)

        historyStart()
        historyStart()

        assertEquals(listOf(AutomationItemEventKind.STATE_CHANGED), events(found).map { it.kind }, "an item with events is left alone")
        val started = events(failed).single()
        assertEquals(1, started.seq)
        assertEquals(AutomationItemEventKind.HISTORY_STARTED, started.kind)
        assertNull(started.fromState)
        assertEquals(AutomationItemState.FAILED, started.toState)
        assertEquals(AutomationStep.RATES, started.step)
        assertEquals(2, started.attempt)
        assertEquals("capture-2", started.captureSessionId)
        assertEquals("op-2", started.operationId)
        assertEquals(31, started.requestsUsed)
        assertEquals("RATES_INCOMPLETE", started.failureCode)
        assertEquals("rates were incomplete", started.failureMessage)
        assertEquals(EVENING.plusSeconds(3600), started.at, "the time it was started, not the time it began")
        val untouched = events(waiting).single()
        assertNull(untouched.step)
        assertNull(untouched.attempt)
        assertEquals(3, itemEvents.count())
    }

    @Test
    fun `a change to an item that has no events starts its history first, with the state the item had before the change`() {
        val seeded = seededCapturing()
        val before = records.toProgress(seeded)

        pipelineTransaction.record(seeded.id, before, before.started("op-1"), null, EVENING)

        val history = events(seeded)
        assertEquals(listOf(1, 2), history.map { it.seq })
        assertEquals(listOf(AutomationItemEventKind.HISTORY_STARTED, AutomationItemEventKind.OPERATION_STARTED), history.map { it.kind })
        with(history.first()) {
            assertEquals(AutomationItemState.CAPTURING, toState)
            assertEquals(AutomationStep.WALLET, step)
            assertEquals(1, attempt)
            assertEquals("capture-1", captureSessionId)
            assertNull(operationId, "the state before the change, without the operation it started")
        }
    }

    @Test
    fun `the startup pass racing a writer of one item leaves that item one HISTORY_STARTED, counts it skipped and carries on with the rest`() {
        val racing = seededCapturing()
        items.save(AutomationRows.item(2, "session-1", AutomationItemState.SIMULATED))
        val before = records.toProgress(racing)
        val template = TransactionTemplate(transactionManager)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val result = try {
            val writer = executor.submit {
                template.execute {
                    pipelineTransaction.record(racing.id, before, before.started("op-1"), null, EVENING)
                    holding.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "the startup pass never reached the item" }
                }
            }
            assertTrue(holding.await(30, TimeUnit.SECONDS))
            val pass = executor.submit<AutomationHistoryStartResult> { historyStart() }
            awaitLockWaiterOr { pass.isDone }
            release.countDown()
            writer.get(30, TimeUnit.SECONDS)
            pass.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertEquals(AutomationHistoryStartResult(started = 1, skipped = 1, failed = 0), result)
        assertEquals(listOf(AutomationItemEventKind.HISTORY_STARTED, AutomationItemEventKind.OPERATION_STARTED), events(racing).map { it.kind })
        assertEquals(listOf(AutomationItemEventKind.HISTORY_STARTED), events(item(2)).map { it.kind })
    }

    @Test
    fun `an item whose start fails is counted and left for the next pass, and the pass starts the others`() {
        (1..3).forEach { items.save(AutomationRows.item(it, "session-1", AutomationItemState.SIMULATED)) }
        val flaky = object : AutomationHistoryStartTransaction(items, records, itemEventWriter) {
            override fun idsAfter(afterId: String): List<String> = historyStartTransaction.idsAfter(afterId)

            override fun start(itemId: String, now: Instant): Boolean =
                if (itemId == "item-2") throw OptimisticLockingFailureException("lost the race") else historyStartTransaction.start(itemId, now)
        }

        val first = historyStart(flaky)
        val next = historyStart()

        assertEquals(AutomationHistoryStartResult(started = 2, skipped = 0, failed = 1), first)
        assertEquals(AutomationHistoryStartResult(started = 1, skipped = 0, failed = 0), next)
        assertEquals(3L, itemEvents.count())
    }

    @Test
    fun `the history start runs before the run search index backfill at startup`() {
        val history = checkNotNull(OrderUtils.getOrder(AutomationHistoryStartRunner::class.java)) { "the history start has no order" }
        val backfill = checkNotNull(OrderUtils.getOrder(RunSearchIndexBackfillRunner::class.java)) { "the backfill has no order" }

        assertTrue(history < backfill)
    }

    @Test
    fun `starting history works through more items than one transaction holds`() {
        val total = 450
        items.saveAll((1..total).map { n ->
            AutomationRows.item(n, "session-1", AutomationItemState.SIMULATED).apply {
                mint = "mint-$n"
                signature = "signature-$n"
            }
        })

        historyStart()

        assertEquals(total.toLong(), itemEvents.count())
        assertEquals(total, itemEvents.findAll().map { it.itemId }.distinct().size)
        assertTrue(itemEvents.findAll().all { it.kind == AutomationItemEventKind.HISTORY_STARTED && it.seq == 1 })
    }

    @Test
    fun `a history that started carries on with the item's next change`() {
        val seeded = seededCapturing()
        historyStart()
        val before = records.toProgress(seeded)

        pipelineTransaction.record(seeded.id, before, before.started("op-1"), null, EVENING)

        assertEquals(listOf(AutomationItemEventKind.HISTORY_STARTED, AutomationItemEventKind.OPERATION_STARTED), events(seeded).map { it.kind })
        assertEquals(listOf(1, 2), events(seeded).map { it.seq })
    }

    private fun seededCapturing(): AutomationItemEntity = items.save(AutomationRows.item(1, "session-1", AutomationItemState.CAPTURING).apply {
        step = AutomationStep.WALLET
        stepAttempt = 1
        captureSessionId = "capture-1"
        nextActionAt = EVENING
    })

    private fun seededCaptured(): AutomationItemEntity = items.save(AutomationRows.item(1, "session-1", AutomationItemState.CAPTURED).apply {
        step = AutomationStep.REGISTER
        stepAttempt = 1
        captureSessionId = "capture-1"
        datasetId = "dataset-1"
    })

    private fun events(item: AutomationItemEntity): List<AutomationItemEventView> =
        query(properties, clock).itemEvents(item.id).events

    private fun historyStart(pages: AutomationHistoryStartTransaction = historyStartTransaction) = AutomationHistoryStartService(pages, clock).start()

    private fun captureSessionId(n: Int): String = "auto-create_session-${item(n).id.take(24)}-1"

    private fun runCapture() {
        ticks(5)
        clock.now = clock.now.plus(MARKET_DELAY)
        ticks(16)
    }

    private fun pipeline() = AutomationPipelineService(pipelineTransaction, discoveryTransaction, capture.sessions, capture.operations, capture.datasets,
        AutomationCaptureCancellation(capture.operations), AutomationCaptureRequestMapperImpl(), usage, properties, holds, clock)

    private fun ticks(count: Int) = repeat(count) {
        pipeline().advance()
        capture.work()
    }

    private fun discover(vararg buys: WalletAction) {
        lookup.actions += buys
        discovery(lookup, properties, clock).discover()
    }

    private fun item(n: Int): AutomationItemEntity = items.findAll().single { it.signature == signature(n).value }

    private companion object {
        val MARKET_DELAY: Duration = Duration.ofMinutes(35)
        val PAGE: PageRequest = PageRequest.of(0, 1000)
    }
}
