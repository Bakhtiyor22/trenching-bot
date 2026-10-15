package com.trenching.bot.console.capture

import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.ProviderRequestLimiter
import com.trenching.bot.chaincapture.CaptureCheckpoint
import com.trenching.bot.console.api.dto.CaptureBudget
import com.trenching.bot.console.api.dto.CaptureOperationRequest
import com.trenching.bot.console.api.dto.CaptureRecoveryConfig
import com.trenching.bot.console.mapper.CaptureResponseMapperImpl
import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.api.dto.CaptureAttemptRequest
import com.trenching.bot.console.api.dto.CaptureCreateRequest
import com.trenching.bot.console.api.dto.CaptureWalletSettings
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCutoffMode
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.marketdata.MarketWindowCutoff
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.replay.CaptureInspection
import com.trenching.bot.replay.CaptureStageState
import com.trenching.bot.replay.ReplayMarketFixtures
import com.trenching.bot.replay.CaptureStageSummary
import com.trenching.bot.replay.CaptureStepCommand
import com.trenching.bot.walletactivity.WalletHistoryCaptureResult
import com.trenching.bot.walletactivity.WalletHistoryScope
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CaptureOperationServiceTest {
    @TempDir lateinit var root: Path

    private fun failure(block: () -> Unit) = assertFailsWith<CaptureRequestException> { block() }

    private fun CaptureConsoleFixture.inspect(requestId: String = "inspect-1", budget: CaptureBudget = CaptureBudget()) =
        operations.start("session-1", CaptureOperationRequest(requestId, CaptureNames.INSPECT, store.session("session-1").marketAttemptId, budget))

    @Test
    fun `an operation is accepted as running and the worker records its checkpoints and result`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }

        val accepted = fixture.inspect()
        assertEquals(CaptureNames.RUNNING, accepted.state)
        assertEquals(CaptureNames.RUNNING, fixture.operations.find("session-1", "inspect-1").state)
        assertTrue(fixture.workflow.executed.isEmpty())

        fixture.executor.runAll()
        val finished = fixture.operations.find("session-1", "inspect-1")
        assertEquals(CaptureNames.SUCCEEDED, finished.state)
        assertEquals(3, finished.progress?.operationRequests)
        assertEquals(5, finished.progress?.cumulativeRequests)
        val session = fixture.sessions.find("session-1")
        assertEquals("inspect-1", session.operation?.id)
        assertNotNull(session.inspection)
    }

    @Test
    fun `the same request id replays its operation and different settings conflict`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        fixture.inspect()

        assertEquals("inspect-1", fixture.inspect().id)
        assertEquals(1, fixture.executor.queued.size)
        assertEquals("REQUEST_CONFLICT", failure { fixture.inspect(budget = CaptureBudget(requestBudget = 3)) }.code)
        fixture.executor.runAll()
        assertEquals(CaptureNames.SUCCEEDED, fixture.inspect().state)
        assertEquals(1, fixture.workflow.executed.size)
    }

    @Test
    fun `a busy worker rejects a different operation in any session`() {
        val fixture = CaptureConsoleFixture(root).apply { create(); create("session-2") }
        fixture.inspect()
        val other = fixture.store.session("session-2")

        val busy = failure { fixture.operations.start("session-2", CaptureOperationRequest("inspect-2", CaptureNames.INSPECT, other.marketAttemptId)) }
        assertEquals(409, busy.status)
        assertEquals("CAPTURE_BUSY", busy.code)
    }

    @Test
    fun `a transient provider failure schedules one bounded retry with its retry after delay`() {
        val scheduler = ManualCaptureRetryScheduler()
        val fixture = CaptureConsoleFixture(root, retryScheduler = scheduler).apply { create() }
        fixture.workflow.failure = CaptureProviderException("RATE_LIMITED", Duration.ofSeconds(40), requestsUsed = 1, bytesUsed = 12)
        val session = fixture.store.session("session-1")

        val accepted = fixture.operations.start("session-1", CaptureOperationRequest(
            "wallet-recovery", CaptureNames.WALLET, session.walletAttemptId,
            CaptureBudget(requestBudget = 2, byteBudget = 100),
            recovery = CaptureRecoveryConfig(totalRequestBudget = 6, totalByteBudget = 300, maxAttempts = 2),
        ))
        assertEquals(1, accepted.recovery?.attemptsUsed)
        assertEquals(2, accepted.recovery?.reservedRequests)
        fixture.executor.runAll()

        val waiting = fixture.operations.find("session-1", accepted.id)
        assertEquals(CaptureNames.WAITING_RETRY, waiting.state)
        assertEquals(1, waiting.recovery?.requestsUsed)
        assertEquals(0, waiting.recovery?.reservedRequests)
        assertEquals(1, scheduler.pending.size)
        assertTrue(scheduler.pending.first().delay >= Duration.ofSeconds(40))
        assertEquals("RETRY_COOLDOWN", failure { fixture.operations.control("session-1", accepted.id, "retry", "retry-now") }.code)

        fixture.workflow.failure = null
        scheduler.runNext()
        val second = fixture.operations.find("session-1", accepted.id)
        assertEquals(CaptureNames.RUNNING, second.state)
        assertEquals(2, second.recovery?.attemptsUsed)
        assertEquals(1, fixture.executor.queued.size)
        fixture.executor.runAll()

        assertEquals(CaptureNames.FAILED, fixture.operations.find("session-1", accepted.id).state)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun `a shared rate limit hold schedules the next attempt no earlier than the hold end and spends no further attempt`() {
        val scheduler = ManualCaptureRetryScheduler()
        val fixture = CaptureConsoleFixture(root, retryScheduler = scheduler).apply { create() }
        val hold = Duration.ofMinutes(25)
        val deadline = com.trenching.bot.chaincapture.CaptureBudget(maxRequests = 10, maxBytes = 1000).also { it.startDeadline(System.nanoTime(), Duration.ofMinutes(1)) }
        fixture.workflow.failure = assertFailsWith<CaptureProviderException> {
            ProviderRequestLimiter(1, Duration.ofMillis(1)).holdStarts(hold, deadline)
        }
        val session = fixture.store.session("session-1")

        val accepted = fixture.operations.start("session-1", CaptureOperationRequest(
            "wallet-hold", CaptureNames.WALLET, session.walletAttemptId,
            CaptureBudget(requestBudget = 2, byteBudget = 100),
            recovery = CaptureRecoveryConfig(totalRequestBudget = 6, totalByteBudget = 300, maxAttempts = 3),
        ))
        fixture.executor.runAll()

        val waiting = fixture.operations.find("session-1", accepted.id)
        assertEquals(CaptureNames.WAITING_RETRY, waiting.state)
        assertEquals(1, waiting.recovery?.attemptsUsed)
        assertEquals(1, scheduler.pending.size)
        assertTrue(scheduler.pending.first().delay >= hold, "scheduled after ${scheduler.pending.first().delay}")
        assertTrue(waiting.recovery?.nextRetryAt?.isBefore(fixture.clock.instant().plus(hold)) == false)
        assertEquals("RETRY_COOLDOWN", failure { fixture.operations.control("session-1", accepted.id, "retry", "retry-now") }.code)
    }

    @Test
    fun `recovery charges each retry slice against the total request ceiling`() {
        val scheduler = ManualCaptureRetryScheduler()
        val fixture = CaptureConsoleFixture(root, retryScheduler = scheduler).apply { create() }
        val session = fixture.store.session("session-1")
        fixture.workflow.checkpointBeforeFailure = CaptureCheckpoint("FAILED_SLICE", FakeCaptureWorkflow.AT, operationRequests = 2, operationBytes = 20)
        fixture.workflow.failure = CaptureProviderException("RATE_LIMITED", requestsUsed = 2, bytesUsed = 20)

        val accepted = fixture.operations.start("session-1", CaptureOperationRequest(
            "bounded-recovery", CaptureNames.WALLET, session.walletAttemptId,
            CaptureBudget(requestBudget = 2, byteBudget = 100),
            recovery = CaptureRecoveryConfig(totalRequestBudget = 3, totalByteBudget = 200, maxAttempts = 3),
        ))
        fixture.executor.runAll()
        assertEquals(2, fixture.operations.find("session-1", accepted.id).recovery?.requestsUsed)

        fixture.workflow.checkpointBeforeFailure = CaptureCheckpoint("FAILED_SLICE", FakeCaptureWorkflow.AT, operationRequests = 1, operationBytes = 10)
        fixture.workflow.failure = CaptureProviderException("RATE_LIMITED", requestsUsed = 1, bytesUsed = 10)
        scheduler.runNext()
        val retry = fixture.operations.find("session-1", accepted.id)
        assertEquals(1, retry.budget.requestBudget)
        assertNull(retry.progress)
        fixture.executor.runAll()

        val exhausted = fixture.operations.find("session-1", accepted.id)
        assertEquals(3, exhausted.recovery?.requestsUsed)
        assertEquals(CaptureNames.FAILED, exhausted.state)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun `restart charges an uncheckpointed reservation and never grants it again`() {
        val scheduler = ManualCaptureRetryScheduler()
        val fixture = CaptureConsoleFixture(root).apply { create() }
        val session = fixture.store.session("session-1")
        val accepted = fixture.operations.start("session-1", CaptureOperationRequest(
            "crashed-recovery", CaptureNames.WALLET, session.walletAttemptId,
            CaptureBudget(requestBudget = 2, byteBudget = 100),
            recovery = CaptureRecoveryConfig(totalRequestBudget = 3, totalByteBudget = 150, maxAttempts = 3),
        ))

        val restartedExecutor = ManualExecutor()
        val restarted = CaptureOperationService(fixture.store, fixture.workflow, CaptureResponseMapperImpl(), fixture.json,
            fixture.clock, restartedExecutor, true, true, scheduler)
        restarted.recover()

        val waiting = restarted.find("session-1", accepted.id)
        assertEquals(CaptureNames.WAITING_RETRY, waiting.state)
        assertEquals(2, waiting.recovery?.requestsUsed)
        assertEquals(100L, waiting.recovery?.bytesUsed)
        assertEquals(0, waiting.recovery?.reservedRequests)
        assertEquals(0, waiting.recovery?.reservedBytes)

        scheduler.runNext()
        val retry = restarted.find("session-1", accepted.id)
        assertEquals(1, retry.budget.requestBudget)
        assertEquals(50L, retry.budget.byteBudget)
    }

    @Test
    fun `pause control remains idempotent after its checkpoint and request ids cannot change action`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        val accepted = fixture.inspect()

        fixture.operations.control("session-1", accepted.id, "pause", "control-1")
        fixture.executor.runAll()

        val paused = fixture.operations.find("session-1", accepted.id)
        assertEquals(CaptureNames.PAUSED, paused.state)
        assertEquals(paused, fixture.operations.control("session-1", accepted.id, "pause", "control-1"))
        assertEquals("REQUEST_CONFLICT", failure { fixture.operations.control("session-1", accepted.id, "cancel", "control-1") }.code)
        assertEquals(CaptureNames.RUNNING, fixture.operations.control("session-1", accepted.id, "resume", "control-2").state)
        fixture.executor.runAll()
        assertEquals(CaptureNames.SUCCEEDED, fixture.operations.find("session-1", accepted.id).state)
    }

    @Test
    fun `the observer reports a pause or cancel request to the capture before its next checkpoint`() {
        val running = CaptureConsoleFixture(Files.createDirectories(root.resolve("running"))).apply { create() }
        val runningBefore = running.workflow.stopRequestedAtStart.size
        running.inspect()
        running.executor.runAll()
        assertEquals(listOf(false), running.workflow.stopRequestedAtStart.drop(runningBefore))

        listOf("pause", "cancel").forEach { action ->
            val fixture = CaptureConsoleFixture(Files.createDirectories(root.resolve(action))).apply { create() }
            val before = fixture.workflow.stopRequestedAtStart.size
            val accepted = fixture.inspect()
            fixture.operations.control("session-1", accepted.id, action, "control-1")
            fixture.executor.runAll()
            assertEquals(listOf(true), fixture.workflow.stopRequestedAtStart.drop(before), action)
        }
    }

    @Test
    fun `a pending pause remains stopped after recovery`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        val session = fixture.store.session("session-1")
        val accepted = fixture.operations.start("session-1", CaptureOperationRequest("pause-recovery", CaptureNames.WALLET,
            session.walletAttemptId, CaptureBudget(requestBudget = 2, byteBudget = 100),
            recovery = CaptureRecoveryConfig(6, 300, 3)))
        fixture.operations.control("session-1", accepted.id, "pause", "pause-control")

        val restarted = CaptureOperationService(fixture.store, fixture.workflow, CaptureResponseMapperImpl(), fixture.json,
            fixture.clock, ManualExecutor(), true, true, ManualCaptureRetryScheduler())
        restarted.recover()

        val paused = restarted.find("session-1", accepted.id)
        assertEquals(CaptureNames.PAUSED, paused.state)
        assertEquals(2, paused.recovery?.requestsUsed)
        assertEquals(listOf("pause:pause-control"), paused.controlRequestIds)
    }

    @Test
    fun `a pending cancel remains cancelled after recovery`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        val session = fixture.store.session("session-1")
        val accepted = fixture.operations.start("session-1", CaptureOperationRequest("cancel-recovery", CaptureNames.WALLET,
            session.walletAttemptId, CaptureBudget(requestBudget = 2, byteBudget = 100),
            recovery = CaptureRecoveryConfig(6, 300, 3)))
        fixture.operations.control("session-1", accepted.id, "cancel", "cancel-control")

        val restarted = CaptureOperationService(fixture.store, fixture.workflow, CaptureResponseMapperImpl(), fixture.json,
            fixture.clock, ManualExecutor(), true, true, ManualCaptureRetryScheduler())
        restarted.recover()

        val cancelled = restarted.find("session-1", accepted.id)
        assertEquals(CaptureNames.CANCELLED, cancelled.state)
        assertEquals(2, cancelled.recovery?.requestsUsed)
        assertEquals(listOf("cancel:cancel-control"), cancelled.controlRequestIds)
    }

    @Test
    fun `missing configuration rejects acquisition before any inspection or provider work`() {
        val fixture = CaptureConsoleFixture(root, rpcConfigured = false, ratesConfigured = false).apply { create() }
        val session = fixture.store.session("session-1")

        assertEquals("NOT_CONFIGURED", failure { fixture.operations.start("session-1", CaptureOperationRequest("wallet-1", CaptureNames.WALLET, session.walletAttemptId)) }.code)
        assertEquals("NOT_CONFIGURED", failure { fixture.operations.start("session-1", CaptureOperationRequest("rates-1", CaptureNames.RATES, session.ratesAttemptId)) }.code)
        assertTrue(fixture.executor.queued.isEmpty())
        assertEquals(0, fixture.workflow.inspections)
        assertTrue(fixture.workflow.executed.isEmpty())
    }

    @Test
    fun `stale attempts invalid budgets and decreasing market pages are rejected`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        val session = fixture.store.session("session-1")

        assertEquals("STALE_ATTEMPT", failure { fixture.operations.start("session-1", CaptureOperationRequest("market-1", CaptureNames.MARKET, "old-attempt")) }.code)
        assertEquals(400, failure { fixture.operations.start("session-1", CaptureOperationRequest("wallet-1", CaptureNames.WALLET, session.walletAttemptId, CaptureBudget(requestBudget = 1001))) }.status)
        assertEquals("RESUME_IDENTITY", failure { fixture.operations.start("session-1", CaptureOperationRequest("market-1", CaptureNames.MARKET, session.marketAttemptId, scanPages = 10)) }.code)
        assertTrue(fixture.executor.queued.isEmpty())
    }

    @Test
    fun `an unmet prerequisite fails the operation without running the step`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        fixture.workflow.inspection = FakeCaptureWorkflow.inspection(CaptureStageSummary(CaptureStageState.INCOMPLETE, "WALLET_CAPTURE_INCOMPLETE", canResume = true))
        val session = fixture.store.session("session-1")

        fixture.operations.start("session-1", CaptureOperationRequest("market-1", CaptureNames.MARKET, session.marketAttemptId))
        fixture.executor.runAll()

        val operation = fixture.operations.find("session-1", "market-1")
        assertEquals(CaptureNames.FAILED, operation.state)
        assertEquals("PREREQUISITE", operation.code)
        assertTrue(fixture.workflow.executed.isEmpty())
    }

    @Test
    fun `failures keep only a safe category and free the worker`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        fixture.workflow.failure = IllegalStateException("https://rpc.example/?api-key=secret")
        fixture.inspect()
        fixture.executor.runAll()

        val failed = fixture.operations.find("session-1", "inspect-1")
        assertEquals(CaptureNames.FAILED, failed.state)
        assertEquals("CAPTURE_FAILED", failed.code)
        assertFalse(failed.message.orEmpty().contains("secret"))

        fixture.workflow.failure = CaptureProviderException("RATE_LIMITED")
        fixture.inspect("inspect-2")
        fixture.executor.runAll()
        assertEquals("RATE_LIMITED", fixture.operations.find("session-1", "inspect-2").code)
    }

    @Test
    fun `a restart marks unfinished operations interrupted and never resumes them`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        fixture.inspect()
        val restartedExecutor = ManualExecutor()

        CaptureOperationService(fixture.store, fixture.workflow, CaptureResponseMapperImpl(), fixture.json, fixture.clock, restartedExecutor, true, true).recover()

        val interrupted = fixture.operations.find("session-1", "inspect-1")
        assertEquals(CaptureNames.INTERRUPTED, interrupted.state)
        assertEquals("PROCESS_RESTART", interrupted.code)
        assertEquals(CaptureNames.INTERRUPTED, fixture.sessions.find("session-1").operation?.state)
        assertTrue(restartedExecutor.queued.isEmpty())
        assertTrue(fixture.workflow.executed.isEmpty())
    }

    @Test
    fun `downloads expose only successful generated artifacts`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }

        assertEquals(404, failure { fixture.operations.artifact("session-1", "dataset") }.status)
        assertEquals(404, failure { fixture.operations.artifact("session-1", "session") }.status)
    }

    @Test
    fun `shutdown stops accepting new operations`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        val operations = CaptureOperationService(fixture.store, fixture.workflow, CaptureResponseMapperImpl(), fixture.json, fixture.clock,
            Executors.newSingleThreadExecutor(), true, true)
        operations.close()

        assertEquals("CAPTURE_BUSY", failure { operations.requireIdle() }.code)
    }

    private fun CaptureConsoleFixture.export(requestId: String = "export-1") =
        operations.start("session-1", CaptureOperationRequest(requestId, CaptureNames.EXPORT, store.session("session-1").marketAttemptId))

    private fun CaptureConsoleFixture.qualify(requestId: String = "qualify-1") =
        operations.start("session-1", CaptureOperationRequest(requestId, CaptureNames.QUALIFY, store.session("session-1").marketAttemptId))

    private fun CaptureConsoleFixture.qualifications() = store.operations("session-1").filter { it.step == CaptureNames.QUALIFY }

    private val verdicts = CaptureQualificationMapper(CaptureResponseMapperImpl())

    @Test
    fun `each console scope maps to its wallet capture request and only the token scope passes its mint`() {
        val fixture = CaptureConsoleFixture(root)
        val cases = listOf(
            CaptureWalletSettings(CaptureConsoleFixture.WALLET, "pumpfun-user-buys", 3, 7) to WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR,
            CaptureWalletSettings(CaptureConsoleFixture.WALLET, "wallet-references", 4, 8) to WalletHistoryScope.WALLET_REFERENCES,
            CaptureWalletSettings(CaptureConsoleFixture.WALLET, "wallet-token", 100, 5, CaptureTokenFixtures.MINT) to WalletHistoryScope.WALLET_TOKEN_ACCOUNT,
        )

        cases.forEachIndexed { index, (settings, scope) ->
            val session = fixture.sessions.create(CaptureCreateRequest("session-$index", "Scope", wallet = settings))
            fixture.settle()
            fixture.operations.start(session.id, CaptureOperationRequest("wallet-$index", CaptureNames.WALLET, session.walletAttemptId))
            fixture.executor.runAll()

            val request = (fixture.workflow.executed.single() as CaptureStepCommand.Wallet).request
            assertEquals(scope, request.scope)
            assertEquals(settings.limit, request.limit)
            assertEquals(settings.scanPages, request.scanPages)
            assertEquals(settings.mint?.let(MintAddress::of), request.targetMint)
            fixture.settle()
        }
    }

    @Test
    fun `each recorded scope maps back to its console settings with the manifest mint`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        val cases = listOf(
            CaptureTokenFixtures.manifest(WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR, limit = 1, scanPages = 20) to
                CaptureWalletSettings(CaptureConsoleFixture.WALLET, "pumpfun-user-buys", 1, 20),
            CaptureTokenFixtures.manifest(WalletHistoryScope.WALLET_REFERENCES, limit = 50, scanPages = 10) to
                CaptureWalletSettings(CaptureConsoleFixture.WALLET, "wallet-references", 50, 10),
            CaptureTokenFixtures.manifest(WalletHistoryScope.WALLET_TOKEN_ACCOUNT, CaptureTokenFixtures.MINT) to
                CaptureWalletSettings(CaptureConsoleFixture.WALLET, "wallet-token", 100, 5, CaptureTokenFixtures.MINT),
        )

        cases.forEachIndexed { index, (manifest, expected) ->
            fixture.workflow.inspection = CaptureTokenFixtures.exportReady().copy(walletCapture = WalletHistoryCaptureResult(root, manifest))
            fixture.inspect("inspect-$index")
            fixture.executor.runAll()
            assertEquals(expected, fixture.sessions.find("session-1").wallet)
        }
    }

    @Test
    fun `a successful wallet-token export queues the offline qualification after it and shows its verdict`() {
        val fixture = CaptureConsoleFixture(root).apply { createToken() }
        fixture.workflow.inspection = CaptureTokenFixtures.exportReady()

        fixture.export()
        assertEquals(1, fixture.executor.queued.size)
        fixture.executor.runAll()

        val commands = fixture.workflow.executed
        assertEquals(listOf(CaptureStepCommand.Export::class, CaptureStepCommand.Qualify::class), commands.map { it::class })
        val export = fixture.operations.find("session-1", "export-1")
        assertEquals(CaptureNames.SUCCEEDED, export.state)
        assertEquals(root.resolve(requireNotNull(export.artifact)).toAbsolutePath().normalize(), (commands[1] as CaptureStepCommand.Qualify).dataset.toAbsolutePath().normalize())
        val qualification = fixture.qualifications().single()
        assertEquals(CaptureNames.SUCCEEDED, qualification.state)
        assertEquals(fixture.store.session("session-1").marketAttemptId, qualification.attemptId)
        val session = fixture.sessions.find("session-1")
        assertEquals(verdicts.map(CaptureTokenFixtures.takeProfit), session.qualification)
        assertEquals(CaptureNames.QUALIFY, session.operation?.step)
    }

    @Test
    fun `an export of another scope queues no qualification and qualifying it is refused`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        fixture.workflow.inspection = CaptureTokenFixtures.exportReady(WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR)

        fixture.export()
        fixture.executor.runAll()

        assertEquals(listOf(CaptureStepCommand.Export::class), fixture.workflow.executed.map { it::class })
        assertTrue(fixture.qualifications().isEmpty())
        assertNull(fixture.sessions.find("session-1").qualification)
        assertTrue(fixture.workflow.verdicts.isEmpty())
        assertEquals("UNSUPPORTED_SCOPE", failure { fixture.qualify() }.code)
    }

    @Test
    fun `qualifying without a successful export of the current attempts is a conflict`() {
        val fixture = CaptureConsoleFixture(root).apply { createToken() }

        val missing = failure { fixture.qualify() }
        assertEquals(409, missing.status)
        assertEquals("EXPORT_REQUIRED", missing.code)
        assertTrue(fixture.executor.queued.isEmpty())

        fixture.workflow.inspection = CaptureTokenFixtures.exportReady()
        fixture.export()
        fixture.settle()
        fixture.sessions.newAttempt("session-1", CaptureAttemptRequest("market-2", CaptureNames.MARKET))
        fixture.settle()

        assertEquals("EXPORT_REQUIRED", failure { fixture.qualify("qualify-2") }.code)
        assertEquals(verdicts.map(CaptureTokenFixtures.inspectionOnly()), fixture.sessions.find("session-1").qualification)
    }

    @Test
    fun `an explicit qualification with the current market attempt replays again without acquisition`() {
        val fixture = CaptureConsoleFixture(root).apply { createToken() }
        fixture.workflow.inspection = CaptureTokenFixtures.exportReady()
        fixture.export()
        fixture.settle()

        assertEquals(CaptureNames.RUNNING, fixture.qualify().state)
        fixture.executor.runAll()

        assertEquals(CaptureNames.SUCCEEDED, fixture.operations.find("session-1", "qualify-1").state)
        assertTrue(fixture.workflow.executed.single() is CaptureStepCommand.Qualify)
    }

    @Test
    fun `until its qualification runs an exported session shows the qualification pending, never not exported`() {
        val fixture = CaptureConsoleFixture(root).apply { createToken() }
        fixture.workflow.inspection = CaptureTokenFixtures.exportReady()

        fixture.export()
        fixture.executor.queued.removeFirst().run()

        assertEquals(1, fixture.executor.queued.size)
        assertEquals(CaptureNames.RUNNING, fixture.qualifications().single().state)
        assertEquals(CaptureTokenFixtures.exportReady() to true, fixture.workflow.verdicts.last())
        val session = fixture.sessions.find("session-1")
        assertTrue(session.datasetReady)
        assertEquals(verdicts.map(CaptureTokenFixtures.inspectionOnly(datasetExported = true)), session.qualification)
        assertEquals("QUALIFICATION_PENDING", session.qualification?.capture?.reason)
    }

    @Test
    fun `a failed automatic qualification leaves the verdict pending and an explicit qualify recovers it`() {
        val fixture = CaptureConsoleFixture(root).apply { createToken() }
        fixture.workflow.inspection = CaptureTokenFixtures.exportReady()
        fixture.export()
        fixture.executor.queued.removeFirst().run()

        fixture.workflow.failure = IllegalStateException("replay worker failed")
        fixture.executor.runAll()
        assertEquals(CaptureNames.FAILED, fixture.qualifications().single().state)
        assertEquals("QUALIFICATION_PENDING", fixture.sessions.find("session-1").qualification?.capture?.reason)

        fixture.workflow.failure = null
        fixture.qualify("qualify-2")
        fixture.executor.runAll()
        assertEquals(verdicts.map(CaptureTokenFixtures.takeProfit), fixture.sessions.find("session-1").qualification)
    }

    @Test
    fun `qualifying an export whose file changed since it was exported is a conflict and runs nothing`() {
        val fixture = CaptureConsoleFixture(root).apply { createToken() }
        fixture.workflow.inspection = CaptureTokenFixtures.exportReady()
        fixture.export()
        fixture.settle()
        val dataset = root.resolve(requireNotNull(fixture.operations.find("session-1", "export-1").artifact))
        java.nio.file.Files.writeString(dataset, FakeCaptureWorkflow.EXPORTED.replace("3", "4"))

        val changed = failure { fixture.qualify("qualify-2") }

        assertEquals(409, changed.status)
        assertEquals("EXPORT_CHANGED", changed.code)
        assertTrue(fixture.executor.queued.isEmpty())
        java.nio.file.Files.delete(dataset)
        assertEquals("EXPORT_CHANGED", failure { fixture.qualify("qualify-3") }.code)
    }

    @Test
    fun `a replay failure is a verdict and the qualification operation still succeeds`() {
        val fixture = CaptureConsoleFixture(root).apply { createToken() }
        fixture.workflow.inspection = CaptureTokenFixtures.exportReady()
        fixture.workflow.qualifyVerdict = CaptureTokenFixtures.replayFailed

        fixture.export()
        fixture.executor.runAll()

        assertEquals(CaptureNames.SUCCEEDED, fixture.qualifications().single().state)
        val verdict = requireNotNull(fixture.sessions.find("session-1").qualification)
        assertEquals("REPLAY_FAILED", verdict.classification)
        assertEquals("FAILED", verdict.replay.state)
        assertEquals("COMPLETE", verdict.capture.state)
    }

    private val walletReady = CaptureStageSummary(CaptureStageState.READY)
    private val marketStartable = FakeCaptureWorkflow.inspection(walletReady, CaptureStageSummary(CaptureStageState.NOT_STARTED, canStart = true))

    private fun CaptureConsoleFixture.market(requestId: String, cutoffMode: String? = null) =
        operations.start("session-1", CaptureOperationRequest(requestId, CaptureNames.MARKET, store.session("session-1").marketAttemptId, cutoffMode = cutoffMode))

    private fun CaptureConsoleFixture.marketModes() = workflow.executed.filterIsInstance<CaptureStepCommand.Markets>().map { it.request.cutoffMode }

    /** Every stage complete, with the market window decided at slot 140; [cutoff] false leaves it captured in full. */
    private fun completeMarket(cutoff: Boolean): CaptureInspection {
        val window = MarketCaptureWindow.of(MintAddress.of(CaptureTokenFixtures.MINT), Slot.of(101), Slot.of(4600), TransactionSignature.of("1".repeat(64)))
        val manifest = ReplayMarketFixtures.manifest(listOf(window))
        val decided = if (cutoff) manifest.copy(windows = manifest.windows.map { it.copy(cutoff = MarketWindowCutoff(140, "TAKE_PROFIT", "engine-v1", "hash")) }) else manifest
        return CaptureTokenFixtures.exportReady().copy(marketCapture = MarketHistoryCaptureResult(root, decided))
    }

    @Test
    fun `a market operation runs in its attempt's cutoff mode unless the request overrides it, and keeps that mode`() {
        val fixture = CaptureConsoleFixture(root).apply { createToken() }
        fixture.workflow.inspection = marketStartable

        fixture.market("market-1")
        fixture.executor.runAll()
        fixture.market("market-2", cutoffMode = "FULL")
        fixture.executor.runAll()

        assertEquals(listOf(MarketCutoffMode.DECIDED, MarketCutoffMode.FULL), fixture.marketModes())
        assertEquals("DECIDED", fixture.operations.find("session-1", "market-1").cutoffMode)
        assertEquals("FULL", fixture.operations.find("session-1", "market-2").cutoffMode)
    }

    @Test
    fun `a market attempt saved without a cutoff mode captures in full`() {
        val fixture = CaptureConsoleFixture(root).apply { createToken() }
        fixture.workflow.inspection = marketStartable
        val attempt = fixture.store.attempt("session-1", requireNotNull(fixture.store.session("session-1").marketAttemptId))
        fixture.store.save("session-1", attempt.copy(market = attempt.market?.copy(cutoffMode = null)))

        fixture.market("market-1")
        fixture.executor.runAll()

        assertEquals(listOf(MarketCutoffMode.FULL), fixture.marketModes())
    }

    @Test
    fun `DECIDED without reference rates is refused before any inspection or provider work`() {
        val fixture = CaptureConsoleFixture(root, ratesConfigured = false).apply { createToken() }
        val attempt = fixture.store.attempt("session-1", requireNotNull(fixture.store.session("session-1").marketAttemptId))

        val requested = failure { fixture.market("market-1", cutoffMode = "DECIDED") }
        fixture.store.save("session-1", attempt.copy(market = attempt.market?.copy(cutoffMode = "DECIDED")))
        val saved = failure { fixture.market("market-2") }

        assertEquals(409 to "RATES_NOT_CONFIGURED", requested.status to requested.code)
        assertEquals(409 to "RATES_NOT_CONFIGURED", saved.status to saved.code)
        assertTrue(fixture.executor.queued.isEmpty())
        assertEquals(0, fixture.workflow.inspections)
        assertNull(fixture.store.operationOrNull("session-1", "market-1"))
    }

    @Test
    fun `a cutoff mode is a per-field error when unknown or sent with a step other than MARKET`() {
        val fixture = CaptureConsoleFixture(root).apply { createToken() }
        val attemptId = fixture.store.session("session-1").marketAttemptId

        val unknown = failure { fixture.market("market-1", cutoffMode = "SOMETIMES") }
        val otherStep = failure { fixture.operations.start("session-1", CaptureOperationRequest("inspect-1", CaptureNames.INSPECT, attemptId, cutoffMode = "FULL")) }

        assertEquals(400 to setOf("cutoffMode"), unknown.status to unknown.fields.keys)
        assertEquals(400 to setOf("cutoffMode"), otherStep.status to otherStep.fields.keys)
        assertTrue(fixture.executor.queued.isEmpty())
    }

    @Test
    fun `a FULL market operation extends a decided capture that is otherwise complete, and only such a capture`() {
        val fixture = CaptureConsoleFixture(root).apply { createToken() }

        fixture.workflow.inspection = completeMarket(cutoff = false)
        fixture.market("full-complete", cutoffMode = "FULL")
        fixture.executor.runAll()
        fixture.workflow.inspection = completeMarket(cutoff = true)
        fixture.market("decided-again")
        fixture.executor.runAll()
        fixture.market("extend", cutoffMode = "FULL")
        fixture.executor.runAll()

        assertEquals("PREREQUISITE", fixture.operations.find("session-1", "full-complete").code)
        assertEquals("PREREQUISITE", fixture.operations.find("session-1", "decided-again").code)
        assertEquals(listOf(MarketCutoffMode.FULL), fixture.marketModes())
        assertEquals("FULL", fixture.operations.find("session-1", "extend").cutoffMode)
    }
}
