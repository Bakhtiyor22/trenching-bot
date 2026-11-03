package com.trenching.bot.console.automation.api.dto

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** [resumeBefore] is set while discovery works through a backlog larger than one scan: the next scan continues from it. */
data class AutomationWatermarkResponse(
    val slot: Long,
    val signature: String,
    val instructionIndex: Int,
    val innerIndex: Int,
    val updatedAt: Instant,
    val resumeBefore: String?,
)

/**
 * [mode] is `ALWAYS` or `WINDOW`; [pollFrom] and [pollTo] are local `HH:mm` times in [zone], and [inPollWindow] whether scheduled
 * discovery scans now by [mode]. [manualRunQueued] is true while a "run now" pass is queued or running. [coveredFrom] is where live
 * coverage starts, and [tradingDayStart] the local `HH:mm` time at which a trading day starts. [requestsLast24h] counts every
 * provider request of the rolling day. [activeBackfillId] is the queued, scanning or capturing backfill, if any.
 */
data class AutomationStatusResponse(
    val enabled: Boolean,
    val captureEnabled: Boolean,
    val wallet: String?,
    val sweepPlanId: String,
    val zone: String,
    val pollFrom: String,
    val pollTo: String,
    val mode: String,
    val inPollWindow: Boolean,
    val manualRunQueued: Boolean,
    val tradingDayStart: String,
    val coveredFrom: Instant?,
    val watermark: AutomationWatermarkResponse?,
    val requestsLast24h: Long,
    val maxRequestsPer24h: Long,
    val rpcConfigured: Boolean,
    val lastTickAt: Instant?,
    val nextTickAt: Instant?,
    val lastDiscoveryAt: Instant?,
    val discoveryProblem: String?,
    val openSessionId: String?,
    val activeBackfillId: String?,
)

/**
 * [itemCounts] has every item state, zeros included; [requestsUsed] sums the provider requests of its items' captures;
 * [tradingDay] is the date of the trading day its first buy falls on; [backfillId] is the backfill whose buys it groups, null for live ones.
 */
data class AutomationSessionResponse(
    val id: String,
    val wallet: String,
    val firstItemId: String,
    val firstBuyAt: Instant,
    val lastBuyAt: Instant,
    val state: String,
    val closedAt: Instant?,
    val closeReason: String?,
    val belowFilterCount: Int,
    val createdAt: Instant,
    val itemCounts: Map<String, Long>,
    val requestsUsed: Long,
    val tradingDay: LocalDate,
    val backfillId: String?,
)

data class AutomationSessionPageResponse(val items: List<AutomationSessionResponse>, val page: Int, val size: Int, val total: Long)

/**
 * [curvePaymentLamports] is an exact integer string, fees excluded. [requestsUsed] is the provider requests of the item's capture
 * session, refreshed whenever a capture step ends; null before any.
 */
data class AutomationItemResponse(
    val id: String,
    val sessionId: String,
    val wallet: String,
    val mint: String,
    val signature: String,
    val instructionIndex: Int,
    val innerIndex: Int,
    val slot: Long,
    val blockTime: Instant?,
    val curvePaymentLamports: String,
    val state: String,
    val step: String?,
    val stepAttempt: Int,
    val captureSessionId: String?,
    val operationId: String?,
    val datasetId: String?,
    val classification: String?,
    val failureCode: String?,
    val failureMessage: String?,
    val nextActionAt: Instant?,
    val requestsUsed: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val backfillId: String?,
)

/** One sweep entry replayed on an item. [runId] opens at `/api/runs/{id}`; without one, [outcome] is the entry's refusal code. */
data class AutomationSweepRunResponse(
    val itemId: String,
    val sessionId: String,
    val planId: String,
    val label: String,
    val runId: String?,
    val configurationHash: String?,
    val engineVersion: String?,
    val outcome: String,
    val positionState: String?,
    val signal: Boolean,
    val realizedPnl: String,
    val worstCasePnl: String,
    val createdAt: Instant,
)

data class AutomationItemDetailResponse(val item: AutomationItemResponse, val sweepRuns: List<AutomationSweepRunResponse>)

/** A sweep run in the sweep run list, with the buy it replayed: its [mint], [signature] and [blockTime]. P&L as in [AutomationSweepRunResponse]. */
data class AutomationSweepRunRowResponse(
    val itemId: String,
    val sessionId: String,
    val mint: String,
    val signature: String,
    val blockTime: Instant?,
    val planId: String,
    val label: String,
    val runId: String?,
    val configurationHash: String?,
    val engineVersion: String?,
    val outcome: String,
    val positionState: String?,
    val signal: Boolean,
    val realizedPnl: String,
    val worstCasePnl: String,
    val createdAt: Instant,
)

data class AutomationSweepRunPageResponse(val items: List<AutomationSweepRunRowResponse>, val page: Int, val size: Int, val total: Long)

/** What a "run now" request did: [queued] a pass, or found one [alreadyQueued] and added nothing. */
data class AutomationManualRunResponse(val queued: Boolean, val alreadyQueued: Boolean)

/**
 * One sweep entry's row for the trading day [day] over [windowDays], oldest first, ending with the day. P&L values are exact SOL
 * decimal strings at scale 12, summed over the signals. [status] is `SUCCESS`, `NO_SUCCESS`, `NO_RESULT` or `PENDING`; a pending
 * row names the oldest incomplete day of its window in [pendingDay] and still carries the counts stored so far.
 */
data class AutomationEvaluationResponse(
    val day: LocalDate,
    val planId: String,
    val label: String,
    val windowDays: List<LocalDate>,
    val signals: Int,
    val scored: Int,
    val unscored: Int,
    val realizedPnl: String,
    val worstCasePnl: String,
    val status: String,
    val pendingDay: LocalDate?,
)

data class AutomationEvaluationPageResponse(val items: List<AutomationEvaluationResponse>, val page: Int, val size: Int, val total: Long)

data class AutomationSessionDetailResponse(val session: AutomationSessionResponse, val items: List<AutomationItemResponse>)

/**
 * A trading day from [from] (inclusive) to [to] (exclusive). [buys] are the items of its sessions, [belowFilter] the buys under
 * the capture filter, [captured] the items whose capture finished, [requestsUsed] their captures' provider requests. [bestStatus]
 * is the day's best row under the active plan, `PENDING` (with [pendingDay]) while a day of its window is incomplete.
 */
data class AutomationDaySummaryResponse(
    val day: LocalDate,
    val from: Instant,
    val to: Instant,
    val covered: Boolean,
    val complete: Boolean,
    val sessions: Int,
    val buys: Int,
    val belowFilter: Int,
    val captured: Int,
    val simulated: Int,
    val failed: Int,
    val abandoned: Int,
    val requestsUsed: Long,
    val bestStatus: String?,
    val bestLabel: String?,
    val pendingDay: LocalDate?,
)

/** The days from [from] to [to] (trading dates, inclusive), newest first. */
data class AutomationDayListResponse(val from: LocalDate, val to: LocalDate, val items: List<AutomationDaySummaryResponse>)

data class AutomationTimeRangeResponse(val from: Instant, val to: Instant)

/** Whether discovery covered all of the day, and the time ranges it has covered for the wallet. */
data class AutomationDayCoverageResponse(val covered: Boolean, val ranges: List<AutomationTimeRangeResponse>)

data class AutomationDayDetailResponse(
    val summary: AutomationDaySummaryResponse,
    val coverage: AutomationDayCoverageResponse,
    val sessions: List<AutomationSessionResponse>,
    val items: List<AutomationItemResponse>,
    val evaluations: List<AutomationEvaluationResponse>,
)

/**
 * A backfill to create (plan Key Decisions 9 and 10). [fromDay] and [toDay] are trading dates, both included; [minSignalSol] and
 * [mints] narrow what becomes an item. Every field is nullable here so a missing one reaches the service as a field error.
 */
data class AutomationBackfillCreateRequest(
    val requestId: String? = null,
    val fromDay: LocalDate? = null,
    val toDay: LocalDate? = null,
    val dryRun: Boolean? = null,
    val requestBudget: Long? = null,
    val minSignalSol: BigDecimal? = null,
    val mints: List<String>? = null,
)

/** A dry run's result: the [distinctMints] bought and the buys passing the filter per trading day. */
data class AutomationBackfillSummaryResponse(val distinctMints: Int, val buysPerDay: Map<LocalDate, Int>)

/**
 * A backfill from [rangeStart] to [rangeEnd] (after clipping to live coverage). [minSignalLamports] is an exact integer string.
 * [discoveryRequests] and [itemRequests] add up to [requestsSpent], which the request budget limits. The counts cover what the scan
 * has read so far: [buysPassing] pass the size and token filters, [itemsCreated] became items, [skippedByFilter] were left out by
 * the token list and [skippedNoBlockTime] actions had no block time, so they could not be placed in the range. [scannedTo] is how
 * far back the scan has read (the range start once it finished), null before a page was read. A [filtered] backfill (a token list,
 * or a minimum signal above the capture minimum) captures its buys but never counts as coverage. [summary] and [estimatedRequests] are a dry run's result, null otherwise; [itemCounts] has every item state.
 */
data class AutomationBackfillResponse(
    val id: String,
    val requestId: String,
    val wallet: String,
    val fromDay: LocalDate,
    val toDay: LocalDate,
    val rangeStart: Instant,
    val rangeEnd: Instant,
    val dryRun: Boolean,
    val minSignalLamports: String?,
    val mints: List<String>,
    val requestBudget: Long,
    val state: String,
    val discoveryRequests: Long,
    val itemRequests: Long,
    val requestsSpent: Long,
    val actionsScanned: Int,
    val buysFound: Int,
    val buysPassing: Int,
    val itemsCreated: Int,
    val skippedByFilter: Int,
    val skippedNoBlockTime: Int,
    val scannedTo: Instant?,
    val filtered: Boolean,
    val summary: AutomationBackfillSummaryResponse?,
    val estimatedRequests: Long?,
    val itemCounts: Map<String, Long>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val finishedAt: Instant?,
)

data class AutomationBackfillPageResponse(val items: List<AutomationBackfillResponse>, val page: Int, val size: Int, val total: Long)
