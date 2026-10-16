package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.CaptureBudget
import com.trenching.bot.console.api.dto.CaptureInspectionResponse
import com.trenching.bot.console.api.dto.CaptureProgressResponse
import com.trenching.bot.console.api.dto.CaptureStageResponse
import com.trenching.bot.console.api.dto.CaptureWindowResponse
import com.trenching.bot.console.capture.CaptureNextActionKind.CAPTURE_RATES
import com.trenching.bot.console.capture.CaptureNextActionKind.INSPECT
import com.trenching.bot.console.capture.CaptureNextActionKind.REGISTER_DATASET
import com.trenching.bot.console.capture.CaptureNextActionKind.RESUME_MARKETS
import com.trenching.bot.console.capture.CaptureNextActionKind.RESUME_WALLET
import com.trenching.bot.console.capture.CaptureNextActionKind.RETRY_WALLET
import com.trenching.bot.console.capture.CaptureNextActionKind.START_MARKETS
import com.trenching.bot.console.capture.CaptureNextActionKind.START_RUN
import com.trenching.bot.console.capture.CaptureNextActionKind.START_WALLET
import com.trenching.bot.console.capture.CaptureNextActionKind.WAIT
import com.trenching.bot.console.capture.CaptureStepStage.DATASET
import com.trenching.bot.console.capture.CaptureStepStage.MARKETS
import com.trenching.bot.console.capture.CaptureStepStage.RATES
import com.trenching.bot.console.capture.CaptureStepStage.WALLET
import com.trenching.bot.console.capture.CaptureSummaryStatus.CANCELLED
import com.trenching.bot.console.capture.CaptureSummaryStatus.DONE
import com.trenching.bot.console.capture.CaptureSummaryStatus.NEEDS_ATTENTION
import com.trenching.bot.console.capture.CaptureSummaryStatus.NOT_STARTED
import com.trenching.bot.console.capture.CaptureSummaryStatus.PAUSED
import com.trenching.bot.console.capture.CaptureSummaryStatus.READY_TO_REPLAY
import com.trenching.bot.console.capture.CaptureSummaryStatus.RUNNING
import com.trenching.bot.console.capture.CaptureSummaryStatus.STOPPED_DEADLINE
import com.trenching.bot.console.capture.CaptureSummaryStatus.STOPPED_LIMIT
import com.trenching.bot.console.capture.CaptureSummaryStatus.WAITING_RETRY
import com.trenching.bot.replay.CaptureStageState
import java.time.Instant
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class CaptureStatusRulesTest {
    data class Case(val label: String, val input: CaptureStatusInput, val status: CaptureSummaryStatus, val step: CaptureStepStage, val action: CaptureNextActionKind) {
        override fun toString() = label
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    fun `derives status current step and next action`(case: Case) {
        val summary = CaptureStatusRules.summarize(case.input)

        assertEquals(case.status, summary.status, "status")
        assertEquals(CaptureCurrentStep(case.step.index, case.step), summary.currentStep, "current step")
        assertEquals(case.action, summary.nextAction.kind, "next action")
    }

    @Test
    fun `an incomplete market operation stopped by its request allowance is a limit stop that resumes the markets with that allowance`() {
        val summary = CaptureStatusRules.summarize(input(
            inspection(CaptureStageState.READY, CaptureStageState.INCOMPLETE, marketReason = "MARKET_CAPTURE_INCOMPLETE"),
            operation(CaptureNames.MARKET, CaptureNames.INCOMPLETE, "REQUESTS", progress(1272, 1697)),
        ))

        assertEquals(STOPPED_LIMIT, summary.status)
        assertEquals("REQUESTS", summary.reasonCode)
        assertEquals(CaptureCurrentStep(2, MARKETS), summary.currentStep)
        assertEquals(CaptureSummaryProgress(1272, 1697, "74"), summary.progress)
        assertEquals(CaptureNextAction(RESUME_MARKETS, MARKETS, "market-1", null, 400), summary.nextAction)
        assertEquals(AT, summary.lastProgressAt)
        assertEquals(listOf(CaptureStepState.DONE, CaptureStepState.STOPPED, CaptureStepState.TODO, CaptureStepState.TODO), summary.steps.map { it.state })
    }

    @Test
    fun `progress is absent while the selection is still being discovered`() {
        val discovering = CaptureStatusRules.summarize(input(inspection(CaptureStageState.READY, CaptureStageState.INCOMPLETE), operation(CaptureNames.MARKET, CaptureNames.RUNNING, progress = progress(40, null))))
        val nothingSelected = CaptureStatusRules.summarize(input(inspection(CaptureStageState.READY, CaptureStageState.INCOMPLETE), operation(CaptureNames.MARKET, CaptureNames.RUNNING, progress = progress(0, 0))))

        assertEquals(RUNNING, discovering.status)
        assertNull(discovering.progress)
        assertNull(nothingSelected.progress)
        assertEquals(CaptureStepState.ACTIVE, discovering.steps.single { it.stage == MARKETS }.state)
    }

    @Test
    fun `percent is floored so it reads 100 only when complete`() {
        assertEquals("99", CaptureStatusRules.summarize(input(operation = operation(CaptureNames.WALLET, CaptureNames.RUNNING, progress = progress(999, 1000)))).progress?.percent)
        assertEquals("100", CaptureStatusRules.summarize(input(operation = operation(CaptureNames.WALLET, CaptureNames.RUNNING, progress = progress(1000, 1000)))).progress?.percent)
    }

    @Test
    fun `case counts come from the market windows and the case records`() {
        val cases = listOf(
            CaptureCaseFact("case-a", null, validated = true, exported = false, datasetId = null),
            CaptureCaseFact("case-b", operation(CaptureNames.RATES, CaptureNames.FAILED, caseId = "case-b"), validated = false, exported = false, datasetId = null),
            CaptureCaseFact("case-c", operation(CaptureNames.RATES, CaptureNames.RUNNING, caseId = "case-c"), validated = false, exported = false, datasetId = null),
        )

        val summary = CaptureStatusRules.summarize(input(inspection(CaptureStageState.READY, CaptureStageState.READY, windows = 5), cases = cases))

        assertEquals(CaptureCaseCounts(total = 5, ready = 1, blocked = 1, downloading = 1), summary.cases)
    }

    @Test
    fun `a ready case without a dataset is registered by its case id`() {
        val summary = CaptureStatusRules.summarize(input(inspection(CaptureStageState.READY, CaptureStageState.READY),
            cases = listOf(CaptureCaseFact("case-a", null, validated = true, exported = true, datasetId = null))))

        assertEquals(CaptureNextAction(REGISTER_DATASET, DATASET, null, "case-a", null), summary.nextAction)
        assertEquals(CaptureStepState.DONE, summary.steps.single { it.stage == RATES }.state)
    }

    @Test
    fun `a fresh session starts its wallet step with the scope's default allowance`() {
        assertEquals(CaptureNextAction(START_WALLET, WALLET, "wallet-1", null, 400), CaptureStatusRules.summarize(input()).nextAction)
        assertEquals(1000, CaptureStatusRules.summarize(input(scope = CaptureWalletScope.WALLET_TOKEN)).nextAction.maxRequests)
    }

    companion object {
        private val AT: Instant = Instant.parse("2026-10-16T20:13:00Z")
        private val NOT = CaptureStageState.NOT_STARTED
        private val READY = CaptureStageState.READY

        private fun stage(state: CaptureStageState, reason: String? = null) = CaptureStageResponse(state.name, reason, false, false, false)

        fun inspection(wallet: CaptureStageState, market: CaptureStageState = NOT, rates: CaptureStageState = NOT, marketReason: String? = null, windows: Int = 0) =
            CaptureInspectionResponse(stage(wallet), stage(market, marketReason), stage(rates), false, false, emptyMap(), emptyMap(), emptyMap(),
                List(windows) { CaptureWindowResponse("mint-$it", 1, 2, "COMPLETE", 1, 1, null, null) })

        fun operation(step: String, state: String, code: String? = null, progress: CaptureProgressResponse? = null, caseId: String? = null) =
            CaptureOperationRecord(id = "op-1", requestId = "op-1", fingerprint = "f", step = step, attemptId = null, state = state, startedAt = AT, updatedAt = AT,
                budget = CaptureBudget(), scanPages = null, progress = progress, code = code, walletAttemptId = "wallet-1", marketAttemptId = "market-1",
                ratesAttemptId = "rates-1", caseId = caseId)

        fun progress(processed: Int?, selected: Int?) = CaptureProgressResponse("DETAILS", AT, 10, 100, 10, 100, processed, selected)

        fun input(inspection: CaptureInspectionResponse? = null, operation: CaptureOperationRecord? = null, datasetReady: Boolean = false, datasetId: String? = null,
                  cases: List<CaptureCaseFact> = emptyList(), scope: CaptureWalletScope? = CaptureWalletScope.PUMPFUN_USER_BUYS) =
            CaptureStatusInput("wallet-1", "market-1", "rates-1", scope, inspection, operation, datasetReady, datasetId, cases)

        private val walletReady = inspection(READY)
        private val marketsReady = inspection(READY, READY)
        private val allReady = inspection(READY, READY, READY)

        @JvmStatic
        fun table(): Stream<Arguments> = Stream.of(
            Case("nothing yet", input(), NOT_STARTED, WALLET, START_WALLET),
            Case("inspected, wallet not started", input(inspection(NOT), operation(CaptureNames.INSPECT, CaptureNames.SUCCEEDED)), NOT_STARTED, WALLET, START_WALLET),
            Case("wallet running", input(inspection(NOT), operation(CaptureNames.WALLET, CaptureNames.RUNNING)), RUNNING, WALLET, WAIT),
            Case("wallet pause requested still runs", input(inspection(NOT), operation(CaptureNames.WALLET, CaptureNames.RUNNING).copy(control = CaptureNames.PAUSE_REQUESTED)), RUNNING, WALLET, WAIT),
            Case("wallet waiting for retry", input(inspection(NOT), operation(CaptureNames.WALLET, CaptureNames.WAITING_RETRY, "RATE_LIMITED")), WAITING_RETRY, WALLET, WAIT),
            Case("wallet paused", input(inspection(CaptureStageState.INCOMPLETE), operation(CaptureNames.WALLET, CaptureNames.PAUSED, "PAUSED_BY_USER")), PAUSED, WALLET, RESUME_WALLET),
            Case("wallet failed", input(inspection(NOT), operation(CaptureNames.WALLET, CaptureNames.FAILED, "CAPTURE_FAILED")), NEEDS_ATTENTION, WALLET, RETRY_WALLET),
            Case("wallet interrupted", input(inspection(CaptureStageState.INCOMPLETE), operation(CaptureNames.WALLET, CaptureNames.INTERRUPTED, "PROCESS_RESTART")), NEEDS_ATTENTION, WALLET, RETRY_WALLET),
            Case("wallet deadline", input(inspection(CaptureStageState.INCOMPLETE), operation(CaptureNames.WALLET, CaptureNames.INCOMPLETE, "DEADLINE")), STOPPED_DEADLINE, WALLET, RESUME_WALLET),
            Case("wallet bytes", input(inspection(CaptureStageState.INCOMPLETE), operation(CaptureNames.WALLET, CaptureNames.INCOMPLETE, "BYTES")), STOPPED_LIMIT, WALLET, RESUME_WALLET),
            Case("wallet history capped", input(inspection(CaptureStageState.INCOMPLETE), operation(CaptureNames.WALLET, CaptureNames.INCOMPLETE, "HISTORY_NOT_EXHAUSTED")), NEEDS_ATTENTION, WALLET, INSPECT),
            Case("wallet provider stop", input(inspection(CaptureStageState.INCOMPLETE), operation(CaptureNames.WALLET, CaptureNames.INCOMPLETE, "TIMEOUT")), NEEDS_ATTENTION, WALLET, RESUME_WALLET),
            Case("wallet cancelled", input(inspection(CaptureStageState.INCOMPLETE), operation(CaptureNames.WALLET, CaptureNames.CANCELLED, "CANCELLED_BY_USER")), CANCELLED, WALLET, RETRY_WALLET),
            Case("stale recovery blocked", input(walletReady, operation(CaptureNames.MARKET, CaptureNames.BLOCKED, "STALE_ATTEMPT")), NEEDS_ATTENTION, MARKETS, INSPECT),
            Case("wallet done, markets not started", input(walletReady, operation(CaptureNames.WALLET, CaptureNames.SUCCEEDED)), NOT_STARTED, MARKETS, START_MARKETS),
            Case("markets stopped by requests", input(inspection(READY, CaptureStageState.INCOMPLETE), operation(CaptureNames.MARKET, CaptureNames.INCOMPLETE, "REQUESTS")), STOPPED_LIMIT, MARKETS, RESUME_MARKETS),
            Case("markets failed", input(inspection(READY, CaptureStageState.INCOMPLETE), operation(CaptureNames.MARKET, CaptureNames.FAILED, "IO")), NEEDS_ATTENTION, MARKETS, RESUME_MARKETS),
            Case("markets incomplete after an inspection", input(inspection(READY, CaptureStageState.INCOMPLETE), operation(CaptureNames.INSPECT, CaptureNames.SUCCEEDED)), PAUSED, MARKETS, RESUME_MARKETS),
            Case("markets invalid", input(inspection(READY, CaptureStageState.INVALID), operation(CaptureNames.INSPECT, CaptureNames.SUCCEEDED)), NEEDS_ATTENTION, MARKETS, INSPECT),
            Case("markets unusable", input(inspection(READY, CaptureStageState.UNUSABLE)), NEEDS_ATTENTION, MARKETS, INSPECT),
            Case("inspection running", input(walletReady, operation(CaptureNames.INSPECT, CaptureNames.RUNNING)), RUNNING, MARKETS, WAIT),
            Case("inspection failed", input(walletReady, operation(CaptureNames.INSPECT, CaptureNames.FAILED, "CAPTURE_FAILED")), NEEDS_ATTENTION, MARKETS, INSPECT),
            Case("markets done, rates not started", input(marketsReady, operation(CaptureNames.MARKET, CaptureNames.SUCCEEDED)), NOT_STARTED, RATES, CAPTURE_RATES),
            Case("rates incomplete", input(inspection(READY, READY, CaptureStageState.INCOMPLETE), operation(CaptureNames.RATES, CaptureNames.INCOMPLETE)), NEEDS_ATTENTION, RATES, CAPTURE_RATES),
            Case("rates failed", input(marketsReady, operation(CaptureNames.RATES, CaptureNames.FAILED, "IO")), NEEDS_ATTENTION, RATES, CAPTURE_RATES),
            Case("all stages ready", input(allReady, operation(CaptureNames.RATES, CaptureNames.SUCCEEDED)), READY_TO_REPLAY, DATASET, REGISTER_DATASET),
            Case("export running", input(allReady, operation(CaptureNames.EXPORT, CaptureNames.RUNNING)), RUNNING, DATASET, WAIT),
            Case("export failed", input(allReady, operation(CaptureNames.EXPORT, CaptureNames.FAILED, "CAPTURE_FAILED")), NEEDS_ATTENTION, DATASET, INSPECT),
            Case("exported, not registered", input(allReady, operation(CaptureNames.QUALIFY, CaptureNames.SUCCEEDED), datasetReady = true), READY_TO_REPLAY, DATASET, REGISTER_DATASET),
            Case("session dataset registered", input(allReady, operation(CaptureNames.QUALIFY, CaptureNames.SUCCEEDED), datasetReady = true, datasetId = "dataset-1"), DONE, DATASET, START_RUN),
            Case("case rates running", input(marketsReady, operation(CaptureNames.MARKET, CaptureNames.SUCCEEDED),
                cases = listOf(CaptureCaseFact("case-a", operation(CaptureNames.RATES, CaptureNames.RUNNING, caseId = "case-a"), false, false, null))), RUNNING, RATES, WAIT),
            Case("case export running", input(marketsReady, cases = listOf(CaptureCaseFact("case-a", operation(CaptureNames.EXPORT, CaptureNames.RUNNING, caseId = "case-a"), true, false, null))), RUNNING, DATASET, WAIT),
            Case("ready case without dataset", input(marketsReady, operation(CaptureNames.MARKET, CaptureNames.SUCCEEDED),
                cases = listOf(CaptureCaseFact("case-a", operation(CaptureNames.RATES, CaptureNames.SUCCEEDED, caseId = "case-a"), true, false, null))), READY_TO_REPLAY, DATASET, REGISTER_DATASET),
            Case("case dataset registered", input(marketsReady, cases = listOf(CaptureCaseFact("case-a", null, true, true, "dataset-1"))), DONE, DATASET, START_RUN),
            Case("case rates failed", input(marketsReady, cases = listOf(CaptureCaseFact("case-a", operation(CaptureNames.RATES, CaptureNames.FAILED, "IO", caseId = "case-a"), false, false, null))), NEEDS_ATTENTION, RATES, CAPTURE_RATES),
            Case("case export failed", input(marketsReady, cases = listOf(CaptureCaseFact("case-a", operation(CaptureNames.EXPORT, CaptureNames.FAILED, "CAPTURE_FAILED", caseId = "case-a"), true, false, null))), NEEDS_ATTENTION, DATASET, INSPECT),
            Case("a failed market run outranks a registered case", input(inspection(READY, CaptureStageState.INCOMPLETE), operation(CaptureNames.MARKET, CaptureNames.FAILED, "IO"),
                cases = listOf(CaptureCaseFact("case-a", null, true, true, "dataset-1"))), NEEDS_ATTENTION, MARKETS, RESUME_MARKETS),
            Case("unknown operation state needs attention", input(walletReady, operation(CaptureNames.MARKET, "SOMETHING_NEW")), NEEDS_ATTENTION, MARKETS, INSPECT),
        ).map { Arguments.of(it) }
    }
}
