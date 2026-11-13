package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationFixtures.WALLET
import com.trenching.bot.console.automation.AutomationFixtures.buy
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.application.AutomationCaptureCancellation
import com.trenching.bot.console.automation.application.AutomationPipelineService
import com.trenching.bot.console.automation.application.AutomationRetryService
import com.trenching.bot.console.automation.application.AutomationRetryTransaction
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationItemNotFoundException
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationRetryRefusal
import com.trenching.bot.console.automation.domain.AutomationRetryRefusedException
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.automation.domain.BackfillState
import com.trenching.bot.console.automation.domain.InvalidAutomationRequestException
import com.trenching.bot.console.automation.domain.SessionCloseReason
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkEntity
import com.trenching.bot.console.automation.mapper.AutomationCaptureRequestMapperImpl
import com.trenching.bot.console.capture.CaptureNames
import com.trenching.bot.console.capture.CaptureOperationRecord
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.replay.CaptureStepCommand
import com.trenching.bot.walletactivity.WalletAction
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Retrying failed buys (plan 20261107, Domain Rules 1-14): the retry over the real pipeline, capture services and a scripted workflow. */
class AutomationRetryServiceTest : AutomationDatabaseTest() {
    @TempDir lateinit var root: Path
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val clock = MutableClock(EVENING)
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
    fun `a failed rates step starts a new operation under the retry's request id on the same attempt and the item ends captured`() {
        failAt(CaptureStepCommand.Rates::class, "RATES_INCOMPLETE")
        val failed = item(1)
        val marketOperations = operations(failed).count { it.step == CaptureNames.MARKET }
        val ratesAttempt = capture.sessions.find(failed.captureSessionId ?: error("created")).ratesAttempt?.id

        val detail = service().retry(failed.id, "retry-1")

        assertEquals(AutomationStep.RATES, failed.step)
        with(detail.item) {
            assertEquals(listOf("CAPTURING", "RATES", 1, 1), listOf(state.name, step?.name, stepAttempt, retries))
            assertNull(failureCode)
            assertNull(failureMessage)
            assertEquals("ITEM_NOT_FAILED", retryRefusal, "an item back in capture is no longer failed")
        }
        ticks(10)
        val captured = item(1)
        assertEquals(AutomationItemState.CAPTURED, captured.state)
        assertEquals(1, captured.retries)
        val rates = operations(captured).filter { it.step == CaptureNames.RATES }
        assertEquals(requestId(1, "rates", retries = 1), rates.single { it.state == CaptureNames.SUCCEEDED }.requestId)
        assertEquals(marketOperations, operations(captured).count { it.step == CaptureNames.MARKET }, "no new market capture")
        assertEquals(ratesAttempt, capture.sessions.find(captured.captureSessionId ?: error("created")).ratesAttempt?.id)
    }

    @Test
    fun `repeating a retry's request id changes nothing, and another id is refused while the item is in capture`() {
        failAt(CaptureStepCommand.Rates::class, "RATES_INCOMPLETE")
        val id = item(1).id
        service().retry(id, "retry-1")
        val afterFirst = items.findOneById(id)

        val repeated = service().retry(id, "retry-1")
        val other = assertFailsWith<AutomationRetryRefusedException> { service().retry(id, "retry-2") }

        assertEquals(afterFirst?.version, items.findOneById(id)?.version)
        assertEquals(afterFirst?.updatedAt, items.findOneById(id)?.updatedAt)
        assertEquals(1, repeated.item.retries)
        assertEquals(AutomationRetryRefusal.ITEM_NOT_FAILED, other.refusal)
    }

    @Test
    fun `a repeated request id returns the item unchanged whatever its state now`() {
        failAt(CaptureStepCommand.Rates::class, "RATES_INCOMPLETE")
        val id = item(1).id
        service().retry(id, "retry-1")
        ticks(10)

        val repeated = service().retry(id, "retry-1")

        assertEquals(AutomationItemState.CAPTURED, repeated.item.state)
        assertEquals(1, repeated.item.retries)
    }

    @Test
    fun `a retried item can fail again and be retried again under a new count in its request ids`() {
        failAt(CaptureStepCommand.Rates::class, "RATES_INCOMPLETE")
        val id = item(1).id
        capture.workflow.fail(CaptureStepCommand.Rates::class, "RATES_INCOMPLETE", times = 2)
        service().retry(id, "retry-1")
        ticks(10)
        assertEquals(AutomationItemState.FAILED, item(1).state)

        service().retry(id, "retry-2")
        ticks(10)

        assertEquals(AutomationItemState.CAPTURED, item(1).state)
        assertEquals(2, item(1).retries)
        assertEquals(1, operations(item(1)).count { it.requestId == requestId(1, "rates", retries = 2) && it.state == CaptureNames.SUCCEEDED })
    }

    @Test
    fun `an item whose capture was deleted from the console is retried from a new capture session`() {
        discover(buy(1, 100, EVENING))
        ticks(1)
        val oldSession = item(1).captureSessionId ?: error("created")
        capture.sessions.delete(oldSession)
        ticks(2)
        assertEquals(AutomationFailures.CAPTURE_NOT_FOUND, item(1).failureCode)

        service().retry(item(1).id, "retry-1")
        ticks(1)

        val retried = item(1)
        assertEquals(requestId(1, "create_session", retries = 1), retried.captureSessionId)
        assertNotEquals(oldSession, retried.captureSessionId)
        assertEquals(AutomationItemState.CAPTURING, retried.state)
        assertEquals(AutomationStep.WALLET, retried.step)
        assertNull(retried.failureCode)
    }

    @Test
    fun `a retried acquisition step still waits for room under the request cap`() {
        failAt(CaptureStepCommand.Markets::class, "MARKET_INCOMPLETE")
        val id = item(1).id
        assertEquals(AutomationStep.MARKET, item(1).step)
        usage.lastDay = properties.maxRequestsPer24h - properties.recovery.requests + 1

        service().retry(id, "retry-1")
        ticks(3)
        val blocked = item(1)
        val marketsWhileBlocked = operations(blocked).count { it.requestId == requestId(1, "market", retries = 1) }
        usage.lastDay = 0
        ticks(2)

        assertEquals(AutomationItemState.BLOCKED_BUDGET, blocked.state)
        assertEquals(0, marketsWhileBlocked)
        assertEquals(1, operations(item(1)).count { it.requestId == requestId(1, "market", retries = 1) })
    }

    @Test
    fun `an item of a closed session is retried and captured, and its session stays closed`() {
        failAt(CaptureStepCommand.Rates::class, "RATES_INCOMPLETE")
        sessions.findAll().forEach { it.state = AutomationSessionState.CLOSED; it.closeReason = SessionCloseReason.IDLE; sessions.save(it) }

        service().retry(item(1).id, "retry-1")
        ticks(10)

        assertEquals(AutomationItemState.CAPTURED, item(1).state)
        assertEquals(AutomationSessionState.CLOSED, sessions.findAll().single().state)
    }

    @Test
    fun `an item that cannot be retried is refused with its code and left as it is`() {
        sessions.save(AutomationRows.session("session-1", AutomationSessionState.OPEN, EVENING))
        val budget = failedRow(1, AutomationFailures.BACKFILL_BUDGET)
        val noTime = failedRow(2, AutomationFailures.MISSING_BLOCK_TIME)
        val abandoned = items.save(AutomationRows.item(3, "session-1", AutomationItemState.ABANDONED))
        val capturing = items.save(AutomationRows.item(4, "session-1", AutomationItemState.CAPTURING))
        val rows = listOf(budget, noTime, abandoned, capturing)

        val refusals = rows.map { row -> assertFailsWith<AutomationRetryRefusedException> { service().retry(row.id, "retry-${row.id}") } }

        assertEquals(listOf(AutomationRetryRefusal.RETRY_NOT_ALLOWED, AutomationRetryRefusal.RETRY_NOT_ALLOWED, AutomationRetryRefusal.ITEM_NOT_FAILED,
            AutomationRetryRefusal.ITEM_NOT_FAILED), refusals.map { it.refusal })
        assertEquals(listOf(AutomationFailures.BACKFILL_BUDGET, AutomationFailures.MISSING_BLOCK_TIME), refusals.take(2).map { it.failureCode })
        assertEquals(listOf(AutomationItemState.FAILED, AutomationItemState.FAILED, AutomationItemState.ABANDONED, AutomationItemState.CAPTURING),
            rows.map { items.findOneById(it.id)?.state })
        assertTrue(listOf(budget, noTime).all { items.findOneById(it.id)?.retries == 0 })
    }

    @Test
    fun `with capture off every retry is refused`() {
        sessions.save(AutomationRows.session("session-1", AutomationSessionState.OPEN, EVENING))
        val failed = failedRow(1, "RATES_INCOMPLETE")
        val captureOff = properties(captureEnabled = false)

        val refused = assertFailsWith<AutomationRetryRefusedException> { retryService(captureOff, clock).retry(failed.id, "retry-1") }

        assertEquals(AutomationRetryRefusal.CAPTURE_DISABLED, refused.refusal)
        assertEquals(AutomationRetryRefusal.CAPTURE_DISABLED.code, query(captureOff, clock).item(failed.id).item.retryRefusal)
        assertEquals(AutomationItemState.FAILED, items.findOneById(failed.id)?.state)
    }

    @Test
    fun `a backfill's item is retried in any backfill state but queued and scanning`() {
        val cases = listOf(BackfillState.CAPTURING to null, BackfillState.STOPPED_BUDGET to null, BackfillState.CANCELLED to null, BackfillState.DONE to null,
            BackfillState.QUEUED to AutomationRetryRefusal.BACKFILL_NOT_CAPTURING, BackfillState.SCANNING to AutomationRetryRefusal.BACKFILL_NOT_CAPTURING)
        cases.forEachIndexed { index, (state, expected) ->
            val n = index + 1
            sessions.save(AutomationRows.session("session-$n", AutomationSessionState.CLOSED, EVENING))
            backfills.save(AutomationBackfillEntity("bf-$n", "req-$n", WALLET, null, LocalDate.parse("2026-10-29"), LocalDate.parse("2026-10-30"),
                EVENING, EVENING.plusSeconds(60), false, null, null, 20_000, state, EVENING, EVENING))
            items.save(AutomationRows.item(n, "session-$n", AutomationItemState.FAILED).apply {
                backfillId = "bf-$n"
                failureCode = "RATES_INCOMPLETE"
                step = AutomationStep.RATES
            })

            assertEquals(expected?.code, query(properties, clock).item("item-$n").item.retryRefusal, state.name)
            if (expected == null) {
                assertEquals("CAPTURING", service().retry("item-$n", "retry-$n").item.state.name, state.name)
            } else {
                assertEquals(expected, assertFailsWith<AutomationRetryRefusedException> { service().retry("item-$n", "retry-$n") }.refusal, state.name)
            }
        }
    }

    @Test
    fun `the item read says whether it can be retried`() {
        sessions.save(AutomationRows.session("session-1", AutomationSessionState.OPEN, EVENING))
        val retryable = failedRow(1, "RATES_INCOMPLETE")
        val budget = failedRow(2, AutomationFailures.BACKFILL_BUDGET)
        val captured = items.save(AutomationRows.item(3, "session-1", AutomationItemState.CAPTURED))
        val query = query(properties, clock)

        assertNull(query.item(retryable.id).item.retryRefusal)
        assertEquals("RETRY_NOT_ALLOWED", query.item(budget.id).item.retryRefusal)
        assertEquals("ITEM_NOT_FAILED", query.item(captured.id).item.retryRefusal)
        assertEquals(listOf(null, "RETRY_NOT_ALLOWED", "ITEM_NOT_FAILED"), query.session("session-1").items.map { it.retryRefusal })
        assertEquals(0, query.item(retryable.id).item.retries)
    }

    @Test
    fun `a request without a valid id is refused and an unknown item is not found`() {
        assertFailsWith<InvalidAutomationRequestException> { service().retry("item-1", null) }
        assertFailsWith<InvalidAutomationRequestException> { service().retry("item-1", "not valid!") }
        assertFailsWith<AutomationItemNotFoundException> { service().retry("missing", "retry-1") }
    }

    @Test
    fun `a day retry retries the allowed failed items oldest first, lists the others by code and is idempotent per item`() {
        sessions.save(AutomationRows.session("session-1", AutomationSessionState.CLOSED, EVENING))
        sessions.save(AutomationRows.session("session-other-day", AutomationSessionState.CLOSED, EVENING.plus(Duration.ofDays(2))))
        failedRow(1, "RATES_INCOMPLETE", blockTime = EVENING.plusSeconds(20))
        failedRow(2, AutomationFailures.BACKFILL_BUDGET, blockTime = EVENING.plusSeconds(10))
        failedRow(3, "WALLET_FAILED", blockTime = EVENING.plusSeconds(5))
        items.save(AutomationRows.item(4, "session-1", AutomationItemState.SIMULATED, blockTime = EVENING))
        items.save(AutomationRows.item(5, "session-other-day", AutomationItemState.FAILED, blockTime = EVENING.plus(Duration.ofDays(2))).apply { failureCode = "X" })

        val first = service().retryDay(LocalDate.parse("2026-11-02"), "day-1")
        val repeat = service().retryDay(LocalDate.parse("2026-11-02"), "day-1")

        assertEquals(LocalDate.parse("2026-11-02"), first.day)
        assertEquals(listOf("item-3", "item-1"), first.retried)
        assertEquals(mapOf("item-2" to "RETRY_NOT_ALLOWED"), first.refused)
        assertEquals(emptyList(), repeat.retried)
        assertEquals(first.refused, repeat.refused)
        assertEquals(listOf(1, 0, 1), listOf(1, 2, 3).map { items.findOneById("item-$it")?.retries })
        assertEquals(AutomationItemState.FAILED, items.findOneById("item-5")?.state)
        assertTrue(items.findOneById("item-1")?.retryRequestId.orEmpty().startsWith("day-1-"))
    }

    @Test
    fun `a day with no failed item retries nothing`() {
        val result = service().retryDay(LocalDate.parse("2026-11-02"), "day-1")

        assertEquals(emptyList(), result.retried)
        assertEquals(emptyMap(), result.refused)
    }

    @Test
    fun `a complete day turns pending while a retried item is in capture`() {
        sessions.save(AutomationRows.session("session-1", AutomationSessionState.CLOSED, EVENING))
        failedRow(1, "RATES_INCOMPLETE", blockTime = EVENING)
        clock.now = EVENING.plus(Duration.ofDays(1))
        watermarks.save(AutomationWatermarkEntity(WALLET, 1, "sig-1", 2, -1, clock.instant()).apply { coveredFrom = EVENING.minus(Duration.ofDays(10)) })
        val days = dayService(properties, clock)
        val day = LocalDate.parse("2026-11-02")

        val before = days.days(day, day).days.single()
        service().retry("item-1", "retry-1")
        val after = days.days(day, day).days.single()

        assertTrue(before.complete)
        assertEquals(false, after.complete)
        assertEquals("PENDING", after.bestStatus)
        assertEquals(AutomationSessionState.CLOSED, sessions.findAll().single().state)
        assertEquals("ITEM_NOT_FAILED", days.day(day).items.single().retryRefusal)
    }

    @Test
    fun `a retried qualify failure starts its own operation under the retry's request id and gets a full round of attempts`() {
        failAt(CaptureStepCommand.Qualify::class, "REPLAY_BROKEN")
        val id = item(1).id
        assertEquals(AutomationStep.QUALIFY, item(1).step)
        capture.workflow.fail(CaptureStepCommand.Qualify::class, "REPLAY_BROKEN", times = 1)

        service().retry(id, "retry-1")
        ticks(10)

        val captured = item(1)
        assertEquals(AutomationItemState.CAPTURED, captured.state)
        val qualifications = operations(captured).filter { it.step == CaptureNames.QUALIFY }.associate { it.requestId to it.state }
        assertEquals(CaptureNames.FAILED, qualifications[requestId(1, "qualify", retries = 1, attempt = 1)], "attempt 1 of the retry ran, and failed")
        assertEquals(CaptureNames.SUCCEEDED, qualifications[requestId(1, "qualify", retries = 1, attempt = 2)], "attempt 2 of the retry ran")
    }

    @Test
    fun `a retried export failure exports again under the retry's request id and the item ends captured`() {
        failAt(CaptureStepCommand.Export::class, "EXPORT_FAILED")
        val id = item(1).id
        assertEquals(AutomationStep.EXPORT, item(1).step)

        service().retry(id, "retry-1")
        ticks(10)

        val captured = item(1)
        assertEquals(AutomationItemState.CAPTURED, captured.state)
        assertEquals(1, captured.retries)
        val exports = operations(captured).filter { it.step == CaptureNames.EXPORT }
        assertEquals(CaptureNames.SUCCEEDED, exports.single { it.requestId == requestId(1, "export", retries = 1) }.state)
    }

    @Test
    fun `a day retry repeated after its item failed again reports the item unchanged, not retried`() {
        sessions.save(AutomationRows.session("session-1", AutomationSessionState.CLOSED, EVENING))
        failedRow(1, "RATES_INCOMPLETE", blockTime = EVENING)
        service().retryDay(LocalDate.parse("2026-11-02"), "day-1")
        items.save((items.findOneById("item-1") ?: error("stored")).apply { state = AutomationItemState.FAILED; failureCode = "RATES_INCOMPLETE" })

        val repeat = service().retryDay(LocalDate.parse("2026-11-02"), "day-1")

        assertEquals(emptyList(), repeat.retried)
        assertEquals(listOf("item-1"), repeat.unchanged)
        assertEquals(emptyMap(), repeat.refused)
        assertEquals(1, items.findOneById("item-1")?.retries)
        assertEquals(AutomationItemState.FAILED, items.findOneById("item-1")?.state)
    }

    @Test
    fun `a retry that races the settling of its backfill is decided again against the ended backfill and the item is still picked up`() {
        sessions.save(AutomationRows.session("session-1", AutomationSessionState.CLOSED, EVENING))
        backfills.save(AutomationBackfillEntity("bf-1", "req-1", WALLET, null, LocalDate.parse("2026-10-29"), LocalDate.parse("2026-10-30"),
            EVENING, EVENING.plusSeconds(60), false, null, null, 20_000, BackfillState.CAPTURING, EVENING, EVENING))
        items.save(failedItem(1).apply { backfillId = "bf-1" })
        var settled = false

        val racing = racingService(onBackfillRead = {
            if (!settled) {
                settled = true
                concurrently { backfillTransaction.settle(clock.instant()) }
            }
        })
        val detail = racing.retry("item-1", "retry-1")

        assertEquals(BackfillState.DONE, backfills.findOneById("bf-1")?.state)
        assertEquals(listOf("CAPTURING", 1), listOf(detail.item.state.name, detail.item.retries))
        assertEquals(listOf("item-1"), pipelineTransaction.due(clock.instant()).map { it.id }, "the retried item of the ended backfill is not stranded")
    }

    @Test
    fun `a failed item of a done backfill is retried and captured, and the backfill and its coverage stay as they were`() {
        assertEndedBackfillItemCaptured(BackfillState.DONE)
    }

    @Test
    fun `a failed item of a cancelled backfill is retried and captured, and the backfill stays cancelled`() {
        assertEndedBackfillItemCaptured(BackfillState.CANCELLED)
    }

    @Test
    fun `a retried item of a backfill whose spend passed its budget still starts, and never fails with the backfill budget code`() {
        failAt(CaptureStepCommand.Markets::class, "MARKET_INCOMPLETE")
        endedBackfill(item(1), BackfillState.DONE, requestBudget = 100, discoveryRequests = 5_000)
        service().retry(item(1).id, "retry-1")

        ticks(3)

        assertEquals(1, operations(item(1)).count { it.requestId == requestId(1, "market", retries = 1) })
        assertNotEquals(AutomationFailures.BACKFILL_BUDGET, item(1).failureCode)
        assertNotEquals(AutomationItemState.FAILED, item(1).state)
    }

    @Test
    fun `a retried item of an ended backfill still waits blocked when the rolling total leaves no room above the reserve`() {
        failAt(CaptureStepCommand.Markets::class, "MARKET_INCOMPLETE")
        endedBackfill(item(1), BackfillState.DONE, requestBudget = 100, discoveryRequests = 5_000)
        usage.lastDay = properties.maxRequestsPer24h - properties.backfillReserveRequests - properties.recovery.requests + 1
        service().retry(item(1).id, "retry-1")

        ticks(3)
        val blocked = item(1)
        val marketsWhileBlocked = operations(blocked).count { it.requestId == requestId(1, "market", retries = 1) }
        usage.lastDay -= 1
        ticks(2)

        assertEquals(AutomationItemState.BLOCKED_BUDGET, blocked.state)
        assertEquals(0, marketsWhileBlocked)
        assertEquals(1, operations(item(1)).count { it.requestId == requestId(1, "market", retries = 1) })
    }

    @Test
    fun `a retried item of an ended backfill is due behind a live item, and a never retried one of it is not due`() {
        sessions.save(AutomationRows.session("session-1", AutomationSessionState.OPEN, EVENING))
        backfills.save(AutomationBackfillEntity("bf-1", "req-1", WALLET, null, LocalDate.parse("2026-10-29"), LocalDate.parse("2026-10-30"),
            EVENING, EVENING.plusSeconds(60), false, null, null, 20_000, BackfillState.DONE, EVENING, EVENING))
        items.save(AutomationRows.item(1, "session-1", AutomationItemState.CAPTURING, blockTime = EVENING.plusSeconds(100)))
        items.save(AutomationRows.item(2, "session-1", AutomationItemState.CAPTURING, blockTime = EVENING).apply { backfillId = "bf-1"; retries = 1 })
        items.save(AutomationRows.item(3, "session-1", AutomationItemState.CAPTURING, blockTime = EVENING).apply { backfillId = "bf-1" })

        val due = pipelineTransaction.due(EVENING.plusSeconds(1)).map { it.id }

        assertEquals(listOf("item-1", "item-2"), due, "live first however old the backfill's buy; item-3 was never retried")
    }

    private fun assertEndedBackfillItemCaptured(state: BackfillState) {
        failAt(CaptureStepCommand.Rates::class, "RATES_INCOMPLETE")
        val id = item(1).id
        val coveredFrom = EVENING.minus(Duration.ofDays(10))
        val watermark = watermarks.findOneByWallet(WALLET) ?: AutomationWatermarkEntity(WALLET, 1, "sig-1", 2, -1, clock.instant())
        watermarks.save(watermark.apply { this.coveredFrom = coveredFrom })
        endedBackfill(item(1), state)

        val detail = service().retry(id, "retry-1")
        ticks(10)

        assertEquals(listOf("CAPTURING", 1), listOf(detail.item.state.name, detail.item.retries))
        val captured = item(1)
        assertEquals(AutomationItemState.CAPTURED, captured.state)
        assertEquals(CaptureNames.SUCCEEDED, operations(captured).single { it.requestId == requestId(1, "rates", retries = 1) }.state)
        assertEquals(state, backfills.findOneById("bf-1")?.state)
        assertEquals(coveredFrom, watermarks.findOneByWallet(WALLET)?.coveredFrom)
    }

    private fun endedBackfill(item: AutomationItemEntity, state: BackfillState, requestBudget: Long = 20_000, discoveryRequests: Long = 0) {
        backfills.save(AutomationBackfillEntity("bf-1", "req-1", WALLET, null, LocalDate.parse("2026-10-29"), LocalDate.parse("2026-10-30"),
            EVENING, EVENING.plusSeconds(60), false, null, null, requestBudget, state, EVENING, EVENING).also { it.discoveryRequests = discoveryRequests })
        items.save(items.findOneById(item.id)?.apply { backfillId = "bf-1" } ?: error("stored"))
    }

    @Test
    fun `a retry that loses twice to concurrent changes is refused from a fresh read, not answered with a conflict`() {
        sessions.save(AutomationRows.session("session-1", AutomationSessionState.OPEN, EVENING))
        items.save(failedItem(1))

        val racing = racingService(onItemRead = { read ->
            when (read) {
                1 -> concurrently { touch("item-1") }
                2 -> concurrently { items.save((items.findOneById("item-1") ?: error("stored")).apply { state = AutomationItemState.ABANDONED }) }
            }
        })
        val refused = assertFailsWith<AutomationRetryRefusedException> { racing.retry("item-1", "retry-1") }

        assertEquals(AutomationRetryRefusal.ITEM_NOT_FAILED, refused.refusal)
        assertEquals(listOf(AutomationItemState.ABANDONED, 0), items.findOneById("item-1")?.let { listOf(it.state, it.retries) })
    }

    @Test
    fun `a retry that loses twice because the same retry committed returns the item unchanged`() {
        sessions.save(AutomationRows.session("session-1", AutomationSessionState.OPEN, EVENING))
        items.save(failedItem(1))

        val racing = racingService(onItemRead = { read ->
            when (read) {
                1 -> concurrently { touch("item-1") }
                2 -> concurrently { retryTransaction.retry("item-1", "retry-1", true, clock.instant()) }
            }
        })
        val detail = racing.retry("item-1", "retry-1")

        assertEquals(listOf("CAPTURING", 1), listOf(detail.item.state.name, detail.item.retries))
        assertEquals(1, items.findOneById("item-1")?.retries)
    }

    private fun failedItem(n: Int): AutomationItemEntity =
        AutomationRows.item(n, "session-1", AutomationItemState.FAILED).apply {
            failureCode = "RATES_INCOMPLETE"
            step = AutomationStep.RATES
            stepAttempt = 2
            captureSessionId = "capture-$n"
        }

    private fun touch(itemId: String) {
        items.save((items.findOneById(itemId) ?: error("stored")).apply { nextActionAt = EVENING.plusSeconds(1) })
    }

    private fun concurrently(action: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit(action).get()
        } finally {
            executor.shutdown()
        }
    }

    /** A retry service whose transaction runs [onItemRead] (with the read's count) after each item read and [onBackfillRead] after each backfill read. */
    private fun racingService(onItemRead: (Int) -> Unit = {}, onBackfillRead: () -> Unit = {}): AutomationRetryService {
        var reads = 0
        val racingItems = object : AutomationItemRepository by items {
            override fun findOneById(id: String): AutomationItemEntity? = items.findOneById(id).also { onItemRead(++reads) }
        }
        val racingBackfills = object : AutomationBackfillRepository by backfills {
            override fun findForRetryById(id: String): AutomationBackfillEntity? = backfills.findForRetryById(id).also { onBackfillRead() }
        }
        val transaction = TransactionalRetry(racingItems, racingBackfills, TransactionTemplate(transactionManager))
        return AutomationRetryService(transaction, sessions, items, query(properties, clock), properties, clock)
    }

    /** The retry transaction run in a real transaction, which a hand-built instance does not get from Spring's proxy. */
    private inner class TransactionalRetry(
        items: AutomationItemRepository,
        backfills: AutomationBackfillRepository,
        private val template: TransactionTemplate,
    ) : AutomationRetryTransaction(items, backfills, records) {
        override fun retry(itemId: String, requestId: String, captureEnabled: Boolean, now: Instant) =
            checkNotNull(template.execute { super.retry(itemId, requestId, captureEnabled, now) })

        override fun afterConflict(itemId: String, requestId: String, captureEnabled: Boolean) =
            checkNotNull(template.execute { super.afterConflict(itemId, requestId, captureEnabled) })
    }

    private fun service() = retryService(properties, clock)

    private fun failedRow(n: Int, code: String, blockTime: Instant? = null): AutomationItemEntity =
        items.save(AutomationRows.item(n, "session-1", AutomationItemState.FAILED, blockTime = blockTime).apply {
            failureCode = code
            step = AutomationStep.RATES
            stepAttempt = 2
            captureSessionId = "capture-$n"
        })

    /** Drives the first buy until [kind] has failed on both attempts and the item is `FAILED`. */
    private fun failAt(kind: KClass<out CaptureStepCommand>, code: String) {
        capture.workflow.fail(kind, code, times = 2)
        discover(buy(1, 100, EVENING))
        ticks(5)
        clock.now = EVENING.plus(MARKET_DELAY)
        ticks(16)
        assertEquals(AutomationItemState.FAILED, item(1).state)
    }

    private fun pipeline() = AutomationPipelineService(pipelineTransaction, discoveryTransaction, capture.sessions, capture.operations, capture.datasets,
        AutomationCaptureCancellation(capture.operations), AutomationCaptureRequestMapperImpl(), usage, properties, clock)

    private fun ticks(count: Int) = repeat(count) {
        pipeline().advance()
        capture.work()
    }

    private fun discover(vararg buys: WalletAction) {
        lookup.actions += buys
        discovery(lookup, properties, clock).discover()
    }

    private fun item(n: Int): AutomationItemEntity = items.findAll().single { it.signature == AutomationFixtures.signature(n).value }

    private fun operations(item: AutomationItemEntity): List<CaptureOperationRecord> =
        item.captureSessionId?.let { capture.store.operations(it) }.orEmpty()

    private fun requestId(n: Int, step: String, retries: Int = 0, attempt: Int = 1): String =
        "auto-$step-${item(n).id.take(24)}-" + (if (retries > 0) "r$retries-" else "") + attempt

    private companion object {
        val MARKET_DELAY: Duration = Duration.ofMinutes(35)
    }
}
