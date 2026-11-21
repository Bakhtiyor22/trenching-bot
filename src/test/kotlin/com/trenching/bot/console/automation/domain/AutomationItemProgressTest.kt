package com.trenching.bot.console.automation.domain

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AutomationItemProgressTest {
    private val now = Instant.parse("2026-11-02T15:00:00Z")
    private val wallet = AutomationItemProgress(AutomationItemState.CAPTURING, AutomationStep.WALLET, 1, "auto-create_session-a-1",
        "auto-wallet-a-1", null, null, null, null, null)

    @Test
    fun `a failure within the attempts runs the same step again under the next attempt`() {
        val retry = wallet.failed("TIMEOUT", "Capture operation stopped: TIMEOUT.", maxAttempts = 2, now = now)

        assertEquals(AutomationItemState.CAPTURING, retry.state)
        assertEquals(AutomationStep.WALLET, retry.step)
        assertEquals(2, retry.stepAttempt)
        assertNull(retry.operationId)
        assertEquals(now, retry.nextActionAt)
        assertEquals("TIMEOUT", retry.failureCode)
    }

    @Test
    fun `the last failed attempt fails the item with the code, the operation and a message cut to 512 characters`() {
        val failed = wallet.copy(stepAttempt = 2).failed("TIMEOUT", "x".repeat(600), maxAttempts = 2, now = now)

        assertEquals(AutomationItemState.FAILED, failed.state)
        assertEquals(2, failed.stepAttempt)
        assertEquals("auto-wallet-a-1", failed.operationId)
        assertEquals(512, failed.failureMessage?.length)
        assertNull(failed.nextActionAt)
    }

    @Test
    fun `a market window over the item's transaction bound fails the item at once, whatever attempts are left`() {
        val market = wallet.copy(step = AutomationStep.MARKET, operationId = "auto-market-a-1")

        val failed = market.failed(AutomationFailures.OVER_CAP, "Too many trades", maxAttempts = 2, now = now)

        assertEquals(AutomationItemState.FAILED, failed.state)
        assertEquals(AutomationStep.MARKET, failed.step)
        assertEquals(1, failed.stepAttempt)
        assertEquals("auto-market-a-1", failed.operationId)
        assertEquals(AutomationFailures.OVER_CAP, failed.failureCode)
        assertNull(failed.nextActionAt)
    }

    @Test
    fun `a discovered item's refused session creation is its first attempt`() {
        val discovered = AutomationItemProgress(AutomationItemState.DISCOVERED, null, 0, null, null, null, null, null, null, now)

        val retry = discovered.failed("INVALID_REQUEST", null, maxAttempts = 2, now = now)

        assertEquals(AutomationStep.CREATE_SESSION, retry.step)
        assertEquals(2, retry.stepAttempt)
    }

    @Test
    fun `a step whose successor starts later waits for it, and one whose successor starts now goes on capturing`() {
        val later = now.plusSeconds(2100)

        val waiting = wallet.succeeded(later, now, "INCOMPLETE_CAPTURE")
        val capturing = wallet.copy(step = AutomationStep.MARKET).succeeded(now, now, null)

        assertEquals(AutomationItemState.WAITING, waiting.state)
        assertEquals(AutomationStep.MARKET, waiting.step)
        assertEquals(later, waiting.nextActionAt)
        assertEquals("INCOMPLETE_CAPTURE", waiting.classification)
        assertEquals(AutomationItemState.CAPTURING, capturing.state)
        assertEquals(AutomationStep.RATES, capturing.step)
        assertFalse(waiting.dueAt(now))
        assertTrue(capturing.dueAt(now))
    }

    @Test
    fun `the request cap admits an operation whose whole allowance fits and refuses one more request`() {
        val cap = AutomationRequestCap(5000, 1200)

        assertTrue(cap.admits(3800))
        assertFalse(cap.admits(3801))
    }

    @Test
    fun `only the first-buy refusals leave a buy unproven`() {
        assertTrue(AutomationFirstBuy.unproven("FIRST_BUY_UNPROVEN"))
        assertTrue(AutomationFirstBuy.unproven("NO_TARGET_BUY"))
        assertTrue(AutomationFirstBuy.unproven("FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT"))
        assertFalse(AutomationFirstBuy.unproven("INCOMPLETE_CAPTURE"))
        assertFalse(AutomationFirstBuy.unproven(null))
    }

    @Test
    fun `a terminal item may not move again`() {
        assertFailsWith<IllegalItemTransitionException> {
            AutomationItemTransitions.require("item-1", AutomationItemState.ABANDONED, AutomationItemState.CAPTURING)
        }
    }

    @Test
    fun `a capture that no longer exists fails the item at once, whatever attempts are left`() {
        val lost = wallet.lost("CAPTURE_NOT_FOUND", "Capture record was not found")

        assertEquals(AutomationItemState.FAILED, lost.state)
        assertEquals(AutomationStep.WALLET, lost.step)
        assertEquals(1, lost.stepAttempt)
        assertEquals("CAPTURE_NOT_FOUND", lost.failureCode)
        assertNull(lost.nextActionAt)
        AutomationItemTransitions.require("item-1", wallet.state, lost.state)
    }

    @Test
    fun `an item whose backfill has no budget left fails at once with the backfill budget code, on its current step`() {
        val stopped = wallet.outOfBudget("Backfill budget would be passed")

        assertEquals(AutomationItemState.FAILED, stopped.state)
        assertEquals(AutomationStep.WALLET, stopped.step)
        assertEquals(AutomationFailures.BACKFILL_BUDGET, stopped.failureCode)
        assertNull(stopped.nextActionAt)
        AutomationItemTransitions.require("item-1", wallet.state, stopped.state)
    }

    @Test
    fun `a failed sweep is tried again after a growing delay under its next attempt, then fails the item`() {
        val captured = AutomationItemProgress(AutomationItemState.CAPTURED, AutomationStep.REGISTER, 1, "capture", null, "dataset",
            "QUALIFYING_DIP", null, null, null)

        val second = captured.sweepFailed("SWEEP_FAILED", "replay broke", maxAttempts = 3, now = now)
        val third = second.sweepFailed("SWEEP_FAILED", "replay broke", maxAttempts = 3, now = now)
        val failed = third.sweepFailed("SWEEP_FAILED", "replay broke", maxAttempts = 3, now = now)

        assertEquals(listOf(AutomationItemState.CAPTURED, AutomationItemState.CAPTURED, AutomationItemState.FAILED), listOf(second, third, failed).map { it.state })
        assertEquals(listOf(2, 3, 3), listOf(second, third, failed).map { it.stepAttempt })
        assertTrue(listOf(second, third, failed).all { it.step == AutomationStep.SWEEP && it.failureCode == "SWEEP_FAILED" })
        assertEquals(now.plus(AutomationLimits.SWEEP_RETRY_DELAY), second.nextActionAt)
        assertEquals(now.plus(AutomationLimits.SWEEP_RETRY_DELAY.multipliedBy(2)), third.nextActionAt)
        assertNull(failed.nextActionAt)
        AutomationItemTransitions.require("item-1", captured.state, second.state)
        AutomationItemTransitions.require("item-1", third.state, failed.state)
    }

    @Test
    fun `a sweep that succeeds on a later attempt keeps that attempt and clears the failure`() {
        val retried = AutomationItemProgress(AutomationItemState.CAPTURED, AutomationStep.SWEEP, 2, "capture", null, "dataset",
            "QUALIFYING_DIP", "SWEEP_FAILED", "replay broke", now)

        val simulated = retried.simulated()

        assertEquals(AutomationItemState.SIMULATED, simulated.state)
        assertEquals(2, simulated.stepAttempt)
        assertNull(simulated.failureCode)
        assertNull(simulated.failureMessage)
    }
    private val failedAtRates = AutomationItemProgress(AutomationItemState.FAILED, AutomationStep.RATES, 2, "capture", "auto-rates-a-2", null,
        "INCOMPLETE_CAPTURE", "INCOMPLETE", "rates incomplete", null)

    @Test
    fun `retrying a failed capture step runs that step again from its first attempt on the same capture session`() {
        val retried = failedAtRates.retried(now)

        assertEquals(AutomationItemState.CAPTURING, retried.state)
        assertEquals(AutomationStep.RATES, retried.step)
        assertEquals(1, retried.stepAttempt)
        assertEquals("capture", retried.captureSessionId)
        assertNull(retried.operationId)
        assertNull(retried.failureCode)
        assertNull(retried.failureMessage)
        assertEquals(now, retried.nextActionAt)
        assertEquals(1, retried.retries)
        assertEquals("INCOMPLETE_CAPTURE", retried.classification)
        assertTrue(retried.dueAt(now))
        AutomationItemTransitions.require("item-1", failedAtRates.state, retried.state, retry = true)
    }

    @Test
    fun `a capture deleted from the console is retried from the session creation with its capture session cleared`() {
        val lost = wallet.lost(AutomationFailures.CAPTURE_NOT_FOUND, "gone")

        val retried = lost.retried(now)

        assertEquals(AutomationItemState.CAPTURING, retried.state)
        assertEquals(AutomationStep.CREATE_SESSION, retried.step)
        assertEquals(1, retried.stepAttempt)
        assertNull(retried.captureSessionId)
        assertNull(retried.operationId)
        assertNull(retried.failureCode)
        assertEquals(1, retried.retries)
    }

    @Test
    fun `a failed sweep is retried as a captured item on the sweep's first attempt, due now`() {
        val failedSweep = AutomationItemProgress(AutomationItemState.CAPTURED, AutomationStep.SWEEP, 2, "capture", null, "dataset",
            "QUALIFYING_DIP", null, null, null).sweepFailed(AutomationFailures.SWEEP_FAILED, "replay broke", maxAttempts = 2, now = now)

        val retried = failedSweep.retried(now)

        assertEquals(AutomationItemState.FAILED, failedSweep.state)
        assertEquals(AutomationItemState.CAPTURED, retried.state)
        assertEquals(AutomationStep.SWEEP, retried.step)
        assertEquals(1, retried.stepAttempt)
        assertEquals(1, retried.sweepAttempt)
        assertEquals("dataset", retried.datasetId)
        assertNull(retried.failureCode)
        assertEquals(now, retried.nextActionAt)
        assertEquals(1, retried.retries)
        AutomationItemTransitions.require("item-1", failedSweep.state, retried.state, retry = true)
    }

    @Test
    fun `the attempts of a retried step run again and the item can fail and be retried again`() {
        val retried = failedAtRates.retried(now)

        val again = retried.failed("INCOMPLETE", null, maxAttempts = 2, now = now)
        val failedAgain = again.failed("INCOMPLETE", null, maxAttempts = 2, now = now)

        assertEquals(2, again.stepAttempt)
        assertEquals(AutomationItemState.FAILED, failedAgain.state)
        assertEquals(1, failedAgain.retries)
        assertEquals(2, failedAgain.retried(now).retries)
    }

    @Test
    fun `only a failed item can be retried`() {
        assertFailsWith<IllegalStateException> { wallet.retried(now) }
    }

    @Test
    fun `the pipeline's own transitions still refuse to leave failed`() {
        assertFailsWith<IllegalItemTransitionException> {
            AutomationItemTransitions.require("item-1", AutomationItemState.FAILED, AutomationItemState.CAPTURING)
        }
        assertFailsWith<IllegalItemTransitionException> {
            AutomationItemTransitions.require("item-1", AutomationItemState.FAILED, AutomationItemState.SIMULATED, retry = true)
        }
        assertFailsWith<IllegalItemTransitionException> {
            AutomationItemTransitions.require("item-1", AutomationItemState.ABANDONED, AutomationItemState.CAPTURING, retry = true)
        }
    }
}
