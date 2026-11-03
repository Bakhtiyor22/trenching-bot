package com.trenching.bot.console.automation

import com.trenching.bot.console.api.dto.CaptureCreateRequest
import com.trenching.bot.console.api.dto.CaptureWalletSettings
import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationFixtures.WALLET
import com.trenching.bot.console.automation.AutomationFixtures.buy
import com.trenching.bot.console.automation.AutomationFixtures.mint
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.AutomationFixtures.signature
import com.trenching.bot.console.automation.application.AutomationCaptureCancellation
import com.trenching.bot.console.automation.application.AutomationPipelineService
import com.trenching.bot.console.automation.application.AutomationPipelineTransaction
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.domain.AutomationDueItem
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationItemProgress
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.automation.domain.BackfillState
import com.trenching.bot.console.automation.domain.IllegalItemTransitionException
import com.trenching.bot.console.automation.domain.SessionCloseReason
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.mapper.AutomationCaptureRequestMapperImpl
import com.trenching.bot.console.capture.CaptureNames
import com.trenching.bot.console.capture.CaptureOperationRecord
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.replay.CaptureStepCommand
import com.trenching.bot.replay.domain.CaseClassification
import com.trenching.bot.walletactivity.WalletAction
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Plan Rules 13–19 and Acceptance Criteria 5 and 8: the item pipeline over the real capture services and a scripted workflow. */
class AutomationPipelineServiceTest : AutomationDatabaseTest() {
    @TempDir lateinit var root: Path

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
    fun `each step runs under its own request id until the item is captured with its registered dataset`() {
        discover(buy(1, 100, EVENING))

        ticks(5)
        clock.now = EVENING.plus(MARKET_DELAY)
        ticks(10)

        val item = item(1)
        assertEquals(AutomationItemState.CAPTURED, item.state)
        assertEquals(requestId("create_session", 1), item.captureSessionId)
        assertEquals(capture.catalog.stored.keys.single(), item.datasetId)
        assertEquals(CaseClassification.QUALIFYING_DIP.name, item.classification)
        assertNull(item.operationId)
        val byStep = operations(item).filter { it.step != CaptureNames.INSPECT }.associate { it.step to it.requestId }
        assertEquals(requestId("wallet", 1), byStep[CaptureNames.WALLET])
        assertEquals(requestId("market", 1), byStep[CaptureNames.MARKET])
        assertEquals(requestId("rates", 1), byStep[CaptureNames.RATES])
        assertEquals(requestId("export", 1), byStep[CaptureNames.EXPORT])
        assertTrue(byStep.getValue(CaptureNames.QUALIFY).startsWith("qualify-"), "the export's own qualification is adopted")
    }

    @Test
    fun `an item waits to create its capture session while its wallet's discovery catch-up is in progress`() {
        discover(buy(1, 100, EVENING))
        lookup.otherTransactions += (200..224).map { signature(it) to 1000L + it }
        clock.now = EVENING.plusSeconds(600)
        val capped = discovery(lookup, properties(captureEnabled = true, maxDiscoveryPages = 1), clock)

        capped.discover()
        ticks(1)
        val duringCatchUp = item(1)
        capped.discover()
        ticks(1)

        assertEquals(AutomationItemState.DISCOVERED, duringCatchUp.state)
        assertNull(duringCatchUp.step)
        assertNull(duringCatchUp.captureSessionId)
        assertEquals(requestId("create_session", 1), item(1).captureSessionId)
    }

    @Test
    fun `the capture session is wallet plus token for the buy's mint, in full, and acquisition sends the automation's allowances`() {
        discover(buy(1, 100, EVENING))

        ticks(5)
        clock.now = EVENING.plus(MARKET_DELAY)
        ticks(2)

        val session = capture.sessions.find(requestId("create_session", 1))
        assertEquals("wallet-token", session.wallet?.scope)
        assertEquals(mint(1).value, session.wallet?.mint)
        assertEquals(WALLET, session.wallet?.wallet)
        assertEquals("FULL", session.marketAttempt?.market?.cutoffMode)
        val acquisitions = operations(item(1)).filter { it.step in setOf(CaptureNames.WALLET, CaptureNames.MARKET) }
        assertEquals(2, acquisitions.size)
        assertTrue(acquisitions.all { it.budget == properties.captureBudget() })
        assertTrue(acquisitions.all { it.recovery?.totalRequestBudget == properties.recovery.requests && it.recovery.maxAttempts == properties.recovery.attempts })
    }

    @Test
    fun `a qualification that failed is started again under the automation request id`() {
        capture.workflow.fail(CaptureStepCommand.Qualify::class, "REPLAY_BROKEN")
        discover(buy(1, 100, EVENING))

        ticks(5)
        clock.now = EVENING.plus(MARKET_DELAY)
        ticks(12)

        assertEquals(AutomationItemState.CAPTURED, item(1).state)
        val qualifications = operations(item(1)).filter { it.step == CaptureNames.QUALIFY }
        assertEquals(2, qualifications.size)
        assertEquals(CaptureNames.FAILED, qualifications.single { it.requestId.startsWith("qualify-") }.state)
        assertEquals(CaptureNames.SUCCEEDED, qualifications.single { it.requestId == requestId("qualify", 1, attempt = 2) }.state)
    }

    @Test
    fun `a restart while the market step runs continues the same operation and creates no second capture session`() {
        discover(buy(1, 100, EVENING))
        ticks(5)
        clock.now = EVENING.plus(MARKET_DELAY)
        pipeline().advance()
        val started = item(1)
        assertEquals(requestId("market", 1), started.operationId)
        items.save(started.apply { state = AutomationItemState.WAITING; operationId = null })

        capture.close()
        capture = CaptureHarness(root, clock)
        ticks(1)

        assertEquals(requestId("market", 1), item(1).operationId)
        assertEquals(AutomationItemState.CAPTURING, item(1).state)
        assertEquals(1, capture.store.sessions().size)
        assertEquals(listOf(requestId("market", 1)), operations(item(1)).filter { it.step == CaptureNames.MARKET }.map { it.id })
    }

    @Test
    fun `a busy capture worker makes the item wait for a later tick`() {
        discover(buy(1, 100, EVENING))
        ticks(1)
        capture.sessions.create(CaptureCreateRequest("manual-1", wallet = CaptureWalletSettings(WALLET)))

        pipeline().advance()
        val waiting = item(1)
        capture.work()
        pipeline().advance()

        assertEquals(AutomationStep.WALLET, waiting.step)
        assertNull(waiting.operationId)
        assertEquals(1, waiting.stepAttempt)
        assertEquals(requestId("wallet", 1), item(1).operationId)
    }

    @Test
    fun `the market step waits until the market delay after the buy`() {
        discover(buy(1, 100, EVENING))
        ticks(5)
        assertEquals(AutomationItemState.WAITING, item(1).state)
        assertEquals(EVENING.plus(MARKET_DELAY), item(1).nextActionAt)

        clock.now = EVENING.plus(MARKET_DELAY).minusSeconds(1)
        ticks(3)
        val early = operations(item(1)).count { it.step == CaptureNames.MARKET }
        clock.now = EVENING.plus(MARKET_DELAY)
        ticks(1)

        assertEquals(0, early)
        assertEquals(1, operations(item(1)).count { it.step == CaptureNames.MARKET })
    }

    @Test
    fun `a failed step is retried once under the next attempt and then fails the item with the operation's code`() {
        capture.workflow.fail(CaptureStepCommand.Wallet::class, "WALLET_BROKEN", times = 2)
        discover(buy(1, 100, EVENING))

        ticks(6)

        val item = item(1)
        assertEquals(AutomationItemState.FAILED, item.state)
        assertEquals(AutomationStep.WALLET, item.step)
        assertEquals(2, item.stepAttempt)
        assertEquals("WALLET_BROKEN", item.failureCode)
        assertTrue(item.failureMessage.orEmpty().contains("WALLET_BROKEN"), item.failureMessage)
        assertNull(item.nextActionAt)
        assertEquals(listOf(requestId("wallet", 1), requestId("wallet", 1, attempt = 2)),
            operations(item).filter { it.step == CaptureNames.WALLET }.map { it.requestId }.sorted())
    }

    @Test
    fun `items wait blocked while the day's requests leave no room for an operation and start once they do`() {
        discover(buy(1, 100, EVENING), buy(2, 101, EVENING.plusSeconds(60)))
        usage.lastDay = properties.maxRequestsPer24h - properties.recovery.requests + 1

        ticks(3)
        val blocked = listOf(item(1), item(2))
        val walletsWhileBlocked = blocked.sumOf { operations(it).count { op -> op.step == CaptureNames.WALLET } }
        usage.lastDay = properties.maxRequestsPer24h - properties.recovery.requests
        pipeline().advance()

        assertEquals(listOf(AutomationItemState.BLOCKED_BUDGET, AutomationItemState.BLOCKED_BUDGET), blocked.map { it.state })
        assertEquals(listOf(AutomationStep.WALLET, AutomationStep.WALLET), blocked.map { it.step })
        assertEquals(0, walletsWhileBlocked)
        assertEquals(AutomationItemState.CAPTURING, item(1).state)
        assertEquals(requestId("wallet", 1), item(1).operationId)
        assertEquals(AutomationItemState.BLOCKED_BUDGET, item(2).state, "one operation at a time, oldest buy first")
    }

    @Test
    fun `an illegal transition is refused and leaves the item unchanged`() {
        discover(buy(1, 100, null))
        val failed = item(1)
        val progress = records.toProgress(failed)

        val refused = assertFailsWith<IllegalItemTransitionException> {
            pipelineTransaction.record(failed.id, progress, progress.copy(state = AutomationItemState.CAPTURING), null, EVENING)
        }

        assertTrue(refused.message.orEmpty().contains("from=FAILED to=CAPTURING"), refused.message)
        assertEquals(AutomationItemState.FAILED, item(1).state)
    }

    @Test
    fun `a buy whose first buy the wallet capture cannot prove is skipped and never captured further`() {
        capture.workflow.verdictClassification = CaseClassification.FIRST_BUY_UNPROVEN
        discover(buy(1, 100, EVENING))

        ticks(4)
        clock.now = EVENING.plus(MARKET_DELAY)
        ticks(3)

        val item = item(1)
        assertEquals(AutomationItemState.SKIPPED_NOT_FIRST_BUY, item.state)
        assertEquals(CaseClassification.FIRST_BUY_UNPROVEN.name, item.classification)
        assertEquals(0, operations(item).count { it.step == CaptureNames.MARKET })
    }

    @Test
    fun `a manual close abandons the pending items, ignores the running operation's result and changes nothing when repeated`() {
        discover(buy(1, 100, EVENING), buy(2, 101, EVENING.plusSeconds(60)))
        ticks(1)
        pipeline().advance()
        val running = item(1).operationId
        val sessionId = item(1).sessionId
        val closing = sessionService(properties, clock, capture.operations)

        closing.close(sessionId, "close-1")
        capture.work()
        clock.now = EVENING.plus(Duration.ofMinutes(5))
        ticks(3)
        val again = closing.close(sessionId, "close-2")

        assertEquals(requestId("wallet", 1), running)
        assertEquals(CaptureNames.SUCCEEDED, capture.operations.find(requestId("create_session", 1), requestId("wallet", 1)).state)
        assertEquals(listOf(AutomationItemState.ABANDONED, AutomationItemState.ABANDONED), listOf(item(1).state, item(2).state))
        assertEquals(running, item(1).operationId)
        assertEquals(0, operations(item(2)).count { it.step == CaptureNames.WALLET })
        assertEquals(EVENING, again.session.session.closedAt)
        assertEquals(AutomationSessionState.CLOSED, again.session.session.state)
    }

    @Test
    fun `an item of a closed session, discovered while capture was off, is captured once capture is on, and its session stays closed`() {
        discover(buy(1, 100, EVENING))
        sessions.findAll().forEach { it.state = AutomationSessionState.CLOSED; it.closeReason = SessionCloseReason.IDLE; sessions.save(it) }

        ticks(5)
        sessionService(properties, clock, capture.operations).closeIdleSessions()

        assertEquals(1, operations(item(1)).count { it.step == CaptureNames.WALLET }, "the phase 0 backlog is captured like any item")
        assertNotNull(item(1).captureSessionId)
        assertEquals(AutomationSessionState.CLOSED, sessions.findAll().single().state, "a session never reopens")
    }

    @Test
    fun `tonight's live items go before the phase 0 backlog of closed sessions, which goes before a backfill's items`() {
        discover(buy(1, 100, EVENING))
        sessions.save(AutomationRows.session("old-night", AutomationSessionState.CLOSED, EVENING.minus(Duration.ofDays(2))))
        items.save(AutomationRows.item(60, "old-night", blockTime = EVENING.minus(Duration.ofDays(2)), nextActionAt = EVENING))
        backfillItem(50, BackfillState.CAPTURING, EVENING.minus(Duration.ofDays(5)))

        val due = pipelineTransaction.due(EVENING).map { it.id }

        assertEquals(listOf(item(1).id, "item-60", "item-50"), due)
    }

    @Test
    fun `in discovery-only mode the pipeline captures nothing`() {
        discover(buy(1, 100, EVENING))

        pipeline(properties(captureEnabled = false)).advance()
        capture.work()

        assertEquals(AutomationItemState.DISCOVERED, item(1).state)
        assertTrue(capture.store.sessions().isEmpty())
    }

    @Test
    fun `each item records its capture's requests when a step ends and its session row sums them`() {
        discover(buy(1, 100, EVENING), buy(2, 101, EVENING.plusSeconds(60)))
        usage.byCapture[requestId("create_session", 1)] = 120
        usage.byCapture[requestId("create_session", 2)] = 80

        ticks(6)

        assertEquals(120, item(1).requestsUsed)
        assertEquals(80, item(2).requestsUsed)
        val query = query(properties, clock, usage)
        assertEquals(120, query.item(item(1).id).item.requestsUsed)
        assertEquals(200L, query.sessions(0, 10, null, null, null, "createdAt,desc").items.single().requestsUsed)
        assertEquals(200L, query.session(item(1).sessionId).session.requestsUsed)
    }

    @Test
    fun `an item whose capture session was deleted from the console fails with CAPTURE_NOT_FOUND instead of erroring every tick`() {
        discover(buy(1, 100, EVENING))
        ticks(1)
        capture.sessions.delete(item(1).captureSessionId ?: error("created"))

        ticks(2)

        val item = item(1)
        assertEquals(AutomationItemState.FAILED, item.state)
        assertEquals(AutomationFailures.CAPTURE_NOT_FOUND, item.failureCode)
        assertEquals(AutomationStep.WALLET, item.step)
        assertEquals(1, item.stepAttempt)
        assertNull(item.nextActionAt)
    }

    @Test
    fun `an item whose followed operation was deleted with its capture fails with CAPTURE_NOT_FOUND`() {
        discover(buy(1, 100, EVENING))
        ticks(1)
        pipeline().advance()
        val followed = item(1).operationId
        capture.work()
        capture.sessions.delete(item(1).captureSessionId ?: error("created"))

        pipeline().advance()

        assertEquals(requestId("wallet", 1), followed)
        assertEquals(AutomationItemState.FAILED, item(1).state)
        assertEquals(AutomationFailures.CAPTURE_NOT_FOUND, item(1).failureCode)
        assertEquals(followed, item(1).operationId)
    }

    @Test
    fun `without an RPC URL the item waits at its wallet step with no attempt charged`() {
        capture.close()
        capture = CaptureHarness(root, clock, rpcConfigured = false)
        discover(buy(1, 100, EVENING))

        ticks(4)

        val item = item(1)
        assertEquals(AutomationItemState.CAPTURING, item.state)
        assertEquals(AutomationStep.WALLET, item.step)
        assertEquals(1, item.stepAttempt)
        assertNull(item.failureCode)
        assertNull(item.operationId)
        assertEquals(0, operations(item).count { it.step == CaptureNames.WALLET })
    }

    @Test
    fun `a manual close cancels the abandoned item's operation waiting for its retry, and only operations the cap admitted run after`() {
        capture.workflow.fail(CaptureStepCommand.Wallet::class, "UNAVAILABLE")
        discover(buy(1, 100, EVENING))
        ticks(1)
        pipeline().advance()
        capture.work()
        val abandoned = item(1)
        val captureSessionId = abandoned.captureSessionId ?: error("created")
        val waitingRetry = capture.operations.find(captureSessionId, abandoned.operationId ?: error("started"))

        sessionService(properties, clock, capture.operations).close(abandoned.sessionId, "close-1")
        discover(buy(2, 300, EVENING.plus(Duration.ofHours(3))))
        usage.lastDay = properties.maxRequestsPer24h - properties.recovery.requests
        ticks(2)
        while (capture.retries.pending.isNotEmpty()) capture.retries.runNext()
        capture.work()

        assertEquals(CaptureNames.WAITING_RETRY, waitingRetry.state)
        val cancelled = capture.operations.find(captureSessionId, waitingRetry.id)
        assertEquals(CaptureNames.CANCELLED, cancelled.state)
        assertEquals(AutomationItemState.ABANDONED, item(1).state)
        assertEquals(requestId("wallet", 2), item(2).operationId)
        assertEquals(2, capture.workflow.executed.count { it is CaptureStepCommand.Wallet }, "the abandoned operation never ran again")
    }

    @Test
    fun `an operation started for an item a manual close abandoned meanwhile is cancelled at once`() {
        discover(buy(1, 100, EVENING))
        ticks(1)
        val closingDuringTick = object : AutomationPipelineTransaction(items, backfills, records) {
            override fun due(now: Instant): List<AutomationDueItem> =
                pipelineTransaction.due(now).onEach { sessionTransaction.closeManually(it.sessionId, now) }

            override fun record(itemId: String, expected: AutomationItemProgress, next: AutomationItemProgress, requestsUsed: Int?, now: Instant): Boolean =
                pipelineTransaction.record(itemId, expected, next, requestsUsed, now)
        }

        pipeline(transaction = closingDuringTick).advance()

        val started = capture.store.operations(item(1).captureSessionId ?: error("created")).single { it.step == CaptureNames.WALLET }
        assertEquals(AutomationItemState.ABANDONED, item(1).state)
        assertNull(item(1).operationId)
        assertEquals(CaptureNames.CANCEL_REQUESTED, started.control)
        assertEquals(listOf("cancel:auto-cancel-wallet-${item(1).id.take(24)}-1"), started.controlRequestIds)
    }

    @Test
    fun `a live item is captured before an older item of a backfill whose scan is over`() {
        discover(buy(1, 100, EVENING))
        backfillItem(50, BackfillState.CAPTURING, EVENING.minus(Duration.ofDays(3)))

        ticks(2)

        assertEquals(1, operations(item(1)).count { it.step == CaptureNames.WALLET })
        assertNotNull(item(50).captureSessionId, "its capture session is made, which needs no capture operation")
        assertEquals(0, operations(item(50)).count { it.step == CaptureNames.WALLET }, "the single capture worker went to the live item first")
    }

    @Test
    fun `a backfill's items are captured oldest buy first once no live item is due`() {
        val backfill = backfillItem(50, BackfillState.CAPTURING, EVENING.minus(Duration.ofDays(2)))
        backfillItem(51, BackfillState.CAPTURING, EVENING.minus(Duration.ofDays(3)), backfillId = backfill)

        ticks(2)

        assertEquals(1, operations(item(51)).count { it.step == CaptureNames.WALLET })
        assertEquals(0, operations(item(50)).count { it.step == CaptureNames.WALLET })
    }

    @Test
    fun `an item of a backfill that is still scanning waits, since an earlier buy of its mint may still replace it`() {
        backfillItem(50, BackfillState.SCANNING, EVENING.minus(Duration.ofDays(3)))

        ticks(4)

        assertEquals(AutomationItemState.DISCOVERED, item(50).state)
        assertNull(item(50).captureSessionId)
    }

    @Test
    fun `a backfill's capture step waits blocked while the day's total plus one allowance would eat into the reserve, and starts once it fits`() {
        backfillItem(50, BackfillState.CAPTURING, EVENING.minus(Duration.ofDays(3)))
        usage.lastDay = properties.maxRequestsPer24h - properties.backfillReserveRequests - properties.recovery.requests + 1

        ticks(3)
        val blocked = item(50)
        val walletsWhileBlocked = operations(blocked).count { it.step == CaptureNames.WALLET }
        usage.lastDay -= 1
        ticks(2)

        assertEquals(AutomationItemState.BLOCKED_BUDGET, blocked.state)
        assertEquals(0, walletsWhileBlocked)
        assertEquals(1, operations(item(50)).count { it.step == CaptureNames.WALLET })
    }

    @Test
    fun `a live item at the total that blocks a backfill's step still starts, since the reserve is kept for it`() {
        discover(buy(1, 100, EVENING))
        usage.lastDay = properties.maxRequestsPer24h - properties.backfillReserveRequests - properties.recovery.requests + 1

        ticks(3)

        assertEquals(1, operations(item(1)).count { it.step == CaptureNames.WALLET })
    }

    @Test
    fun `an operation is started while the backfill's spend plus one recovery allowance stays within its budget`() {
        backfillItem(50, BackfillState.CAPTURING, EVENING.minus(Duration.ofDays(3)), requestBudget = 2000, discoveryRequests = 800)

        ticks(2)

        assertEquals(1, operations(item(50)).count { it.step == CaptureNames.WALLET })
        assertEquals(AutomationItemState.CAPTURING, item(50).state)
    }

    @Test
    fun `an item whose backfill cannot pay another operation fails unscored with the backfill budget code, and the backfill stops once nothing is left`() {
        val backfill = backfillItem(50, BackfillState.CAPTURING, EVENING.minus(Duration.ofDays(3)), requestBudget = 2000, discoveryRequests = 801)
        backfillItem(51, BackfillState.CAPTURING, EVENING.minus(Duration.ofDays(2)), backfillId = backfill, requestBudget = 2000, discoveryRequests = 801)

        ticks(4)
        backfillService(properties, clock, capture.operations).settle()

        listOf(50, 51).forEach {
            assertEquals(AutomationItemState.FAILED, item(it).state)
            assertEquals(AutomationFailures.BACKFILL_BUDGET, item(it).failureCode)
            assertEquals(AutomationStep.WALLET, item(it).step)
            assertEquals(0, operations(item(it)).count { op -> op.step == CaptureNames.WALLET })
        }
        assertEquals(BackfillState.STOPPED_BUDGET, backfills.findById(backfill).orElseThrow().state)
        assertNull(backfills.findById(backfill).orElseThrow().activeWallet)
    }

    @Test
    fun `cancelling a backfill abandons its item and cancels the capture operation the item had started`() {
        val backfill = backfillItem(50, BackfillState.CAPTURING, EVENING.minus(Duration.ofDays(3)))
        ticks(1)
        pipeline().advance()
        val started = capture.store.operations(item(50).captureSessionId ?: error("created")).single { it.step == CaptureNames.WALLET }

        backfillService(properties, clock, capture.operations).cancel(backfill, "cancel-1")

        assertEquals(AutomationItemState.ABANDONED, item(50).state)
        assertEquals(CaptureNames.CANCEL_REQUESTED, capture.store.operations(item(50).captureSessionId ?: error("created")).single { it.id == started.id }.control)
        assertEquals(BackfillState.CANCELLED, backfills.findById(backfill).orElseThrow().state)
    }

    @Test
    fun `the spend counts the captures the backfill's items have used so far`() {
        val backfill = backfillItem(50, BackfillState.CAPTURING, EVENING.minus(Duration.ofDays(3)), requestBudget = 2500)
        backfillItem(51, BackfillState.CAPTURING, EVENING.minus(Duration.ofDays(2)), backfillId = backfill, requestBudget = 2500)
        items.findAll().single { it.id == "item-50" }.let { items.save(it.also { done -> done.state = AutomationItemState.SIMULATED; done.requestsUsed = 1301 }) }

        ticks(3)

        assertEquals(AutomationFailures.BACKFILL_BUDGET, item(51).failureCode, "1,301 spent plus the 1,200 allowance passes 2,500")
    }

    private fun pipeline(settings: AutomationProperties = properties, transaction: AutomationPipelineTransaction = pipelineTransaction) =
        AutomationPipelineService(transaction, discoveryTransaction, capture.sessions, capture.operations, capture.datasets, AutomationCaptureCancellation(capture.operations),
            AutomationCaptureRequestMapperImpl(), usage, settings, clock)

    /** One scheduler tick after another, each followed by the capture worker running what was queued. */
    private fun ticks(count: Int) = repeat(count) {
        pipeline().advance()
        capture.work()
    }

    /** An item of a backfill in [state] with the budget given, in a closing session of its own; returns the backfill's id. */
    private fun backfillItem(
        n: Int,
        state: BackfillState,
        blockTime: Instant,
        backfillId: String = "bf-$n",
        requestBudget: Long = 20_000,
        discoveryRequests: Long = 0,
    ): String {
        if (!backfills.existsById(backfillId)) {
            backfills.save(AutomationBackfillEntity(backfillId, "req-$backfillId", WALLET, if (state.active) WALLET else null, LocalDate.parse("2026-10-29"),
                LocalDate.parse("2026-10-30"), blockTime.minusSeconds(3600), blockTime.plusSeconds(3600), false, null, null, requestBudget, state, EVENING, EVENING)
                .also { it.discoveryRequests = discoveryRequests })
        }
        sessions.save(AutomationRows.session("session-$n", AutomationSessionState.CLOSING, blockTime).also { it.backfillId = backfillId })
        items.save(AutomationRows.item(n, "session-$n", blockTime = blockTime, nextActionAt = EVENING).also { it.backfillId = backfillId })
        return backfillId
    }

    private fun discover(vararg buys: WalletAction) {
        lookup.actions += buys
        discovery(lookup, properties, clock).discover()
    }

    private fun item(n: Int): AutomationItemEntity = items.findAll().single { it.signature == signature(n).value }

    private fun operations(item: AutomationItemEntity): List<CaptureOperationRecord> =
        item.captureSessionId?.let { capture.store.operations(it) }.orEmpty()

    private fun requestId(step: String, n: Int, attempt: Int = 1): String = "auto-$step-${item(n).id.take(24)}-$attempt"

    private companion object {
        val MARKET_DELAY: Duration = Duration.ofMinutes(35)
    }
}
