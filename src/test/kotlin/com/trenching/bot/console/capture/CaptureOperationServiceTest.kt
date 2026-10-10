package com.trenching.bot.console.capture

import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.console.api.dto.CaptureBudget
import com.trenching.bot.console.api.dto.CaptureOperationRequest
import com.trenching.bot.console.mapper.CaptureResponseMapperImpl
import com.trenching.bot.replay.CaptureStageState
import com.trenching.bot.replay.CaptureStageSummary
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
}
