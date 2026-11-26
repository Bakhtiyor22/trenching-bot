package com.trenching.bot.console.capture

import com.trenching.bot.chaincapture.RpcEndpointChoice
import com.trenching.bot.chaincapture.RpcEndpointTarget
import com.trenching.bot.console.api.dto.CaptureOperationRequest
import com.trenching.bot.console.solana.accounts.ScriptedRpcEndpoints
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A WALLET or MARKET operation is refused at its start while no RPC account has credits, before any work is queued. */
class CaptureOperationCreditsTest {
    @TempDir
    lateinit var root: Path

    private val endpoints = ScriptedRpcEndpoints(RpcEndpointChoice.OutOfCredits)

    private fun refused(step: String, attempt: (com.trenching.bot.console.capture.CaptureSessionRecord) -> String?): CaptureRequestException {
        val fixture = CaptureConsoleFixture(root, endpoints = endpoints).apply { create() }
        val session = fixture.store.session("session-1")
        val failure = assertFailsWith<CaptureRequestException> { fixture.operations.start("session-1", CaptureOperationRequest("op-1", step, attempt(session))) }
        assertTrue(fixture.executor.queued.isEmpty())
        assertTrue(fixture.workflow.executed.isEmpty())
        return failure
    }

    @Test
    fun `a wallet operation is refused as RPC_CREDITS_EXHAUSTED before any work is queued`() {
        val failure = refused(CaptureNames.WALLET) { it.walletAttemptId }

        assertEquals(409 to "RPC_CREDITS_EXHAUSTED", failure.status to failure.code)
    }

    @Test
    fun `a market operation is refused as RPC_CREDITS_EXHAUSTED too`() {
        val failure = refused(CaptureNames.MARKET) { it.marketAttemptId }

        assertEquals("RPC_CREDITS_EXHAUSTED", failure.code)
    }

    @Test
    fun `an operation that spends no RPC credits still starts while every account is out`() {
        val fixture = CaptureConsoleFixture(root, endpoints = endpoints).apply { create() }
        val session = fixture.store.session("session-1")

        val started = fixture.operations.start("session-1", CaptureOperationRequest("rates-1", CaptureNames.RATES, session.ratesAttemptId))

        assertEquals(CaptureNames.RATES, started.step)
        assertEquals(1, fixture.executor.queued.size)
    }

    @Test
    fun `the refusal ends as soon as an account has credits`() {
        val fixture = CaptureConsoleFixture(root, endpoints = endpoints).apply { create() }
        val session = fixture.store.session("session-1")
        val refusal = assertFailsWith<CaptureRequestException> { fixture.operations.start("session-1", CaptureOperationRequest("wallet-1", CaptureNames.WALLET, session.walletAttemptId)) }
        assertEquals("RPC_CREDITS_EXHAUSTED", refusal.code)

        endpoints.choice = RpcEndpointChoice.Use(RpcEndpointTarget("backup", "https://backup.example.test/?api-key=BACKUP-KEY"))
        val started = fixture.operations.start("session-1", CaptureOperationRequest("wallet-1", CaptureNames.WALLET, session.walletAttemptId))

        assertEquals(CaptureNames.WALLET, started.step)
        assertEquals(1, fixture.executor.queued.size)
    }

    @Test
    fun `an unconfigured RPC is still refused as NOT_CONFIGURED, not as out of credits`() {
        val fixture = CaptureConsoleFixture(root, rpcConfigured = false, endpoints = endpoints).apply { create() }
        val session = fixture.store.session("session-1")

        val failure = assertFailsWith<CaptureRequestException> { fixture.operations.start("session-1", CaptureOperationRequest("wallet-1", CaptureNames.WALLET, session.walletAttemptId)) }

        assertEquals("NOT_CONFIGURED", failure.code)
    }
}
