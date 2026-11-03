package com.trenching.bot.console.automation.domain

import com.trenching.bot.portfolio.domain.PositionState
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** [backfillId] is the backfill whose buys the session groups, null for a session of live discovery. */
data class AutomationSessionView(
    val id: String,
    val wallet: String,
    val firstItemId: String,
    val firstBuyAt: Instant,
    val lastBuyAt: Instant,
    val state: AutomationSessionState,
    val closedAt: Instant?,
    val closeReason: SessionCloseReason?,
    val belowFilterCount: Int,
    val createdAt: Instant,
    val backfillId: String?,
)

/**
 * A session with its item counts for every state (zeros included), the provider requests its items' captures used, and the
 * trading day it belongs to (named by its start date, plan Rule 30).
 */
data class AutomationSessionRow(
    val session: AutomationSessionView,
    val itemCounts: Map<AutomationItemState, Long>,
    val requestsUsed: Long,
    val tradingDay: LocalDate,
)

/**
 * [requestsUsed] is the provider requests of the item's capture session so far, refreshed whenever a capture step ends;
 * [backfillId] is the backfill that found the buy, null for a live one.
 */
data class AutomationItemView(
    val id: String,
    val sessionId: String,
    val wallet: String,
    val mint: String,
    val signature: String,
    val instructionIndex: Int,
    val innerIndex: Int,
    val slot: Long,
    val blockTime: Instant?,
    val curvePaymentLamports: BigInteger,
    val state: AutomationItemState,
    val step: AutomationStep?,
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

/** One sweep entry replayed on an item: its run, or in [outcome] the refusal code of an entry that got none. P&L is SOL at scale 12. */
data class AutomationSweepRunView(
    val itemId: String,
    val sessionId: String,
    val planId: String,
    val label: String,
    val runId: String?,
    val configurationHash: String?,
    val engineVersion: String?,
    val outcome: String,
    val positionState: PositionState?,
    val signal: Boolean,
    val realizedPnl: BigDecimal,
    val worstCasePnl: BigDecimal,
    val createdAt: Instant,
)

data class AutomationItemDetail(val item: AutomationItemView, val sweepRuns: List<AutomationSweepRunView>)

/** A sweep run in the sweep run list, with the buy it replayed. */
data class AutomationSweepRunRow(val run: AutomationSweepRunView, val mint: String, val signature: String, val blockTime: Instant?)

data class AutomationSessionDetail(val session: AutomationSessionRow, val items: List<AutomationItemView>)

/** [resumeBefore] is set while discovery works through a backlog larger than one scan: the next scan continues from it. */
data class AutomationWatermarkView(
    val slot: Long,
    val signature: String,
    val instructionIndex: Int,
    val innerIndex: Int,
    val updatedAt: Instant,
    val resumeBefore: String?,
)

/** The in-process record of the scheduler's last runs; [discoveryProblem] is set while the provider cannot be read. */
data class AutomationHeartbeat(
    val lastTickAt: Instant?,
    val lastDiscoveryAt: Instant?,
    val discoveryProblem: WalletActivityUnavailable?,
) {
    companion object {
        val NONE = AutomationHeartbeat(null, null, null)
    }
}

/**
 * Automation's settings and state for the console. [mode] is how scheduled discovery decides to scan, and [inPollWindow] whether
 * it scans now by that rule. [manualRunQueued] is true while a "run now" pass is queued or running. [coveredFrom] is where live
 * coverage starts (plan Rule 32), and [tradingDayStart] the local time in [zone] at which a trading day starts.
 * [requestsLast24h] counts every provider request of the rolling day, manual captures included, against [maxRequestsPer24h].
 * [nextTickAt] is null until the scheduler has ticked. [activeBackfillId] is the wallet's queued, scanning or capturing backfill, if any.
 */
data class AutomationStatus(
    val enabled: Boolean,
    val captureEnabled: Boolean,
    val wallet: String?,
    val sweepPlanId: String,
    val zone: ZoneId,
    val pollFrom: LocalTime,
    val pollTo: LocalTime,
    val mode: DiscoveryMode,
    val inPollWindow: Boolean,
    val manualRunQueued: Boolean,
    val tradingDayStart: LocalTime,
    val coveredFrom: Instant?,
    val watermark: AutomationWatermarkView?,
    val requestsLast24h: Long,
    val maxRequestsPer24h: Long,
    val rpcConfigured: Boolean,
    val lastTickAt: Instant?,
    val nextTickAt: Instant?,
    val lastDiscoveryAt: Instant?,
    val discoveryProblem: WalletActivityUnavailable?,
    val openSessionId: String?,
    val activeBackfillId: String?,
)

/**
 * One trading day at a glance (plan Rules 32-34). [buys] are the items of its sessions, [belowFilter] the buys the capture filter
 * skipped, [captured] the items whose capture finished (captured or simulated), and [requestsUsed] their captures' provider
 * requests. [bestStatus] and [bestLabel] are the day's best row under the active plan, or `PENDING` with [pendingDay] and no
 * label while a day of its window is incomplete.
 */
data class DaySummary(
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

/** The days from [from] to [to], newest first. */
data class DayList(val from: LocalDate, val to: LocalDate, val days: List<DaySummary>)

/** Whether live discovery (and, later, finished backfills) covered all of a day, and the ranges that are covered. */
data class DayCoverage(val covered: Boolean, val ranges: List<TimeRange>)

/** One trading day: its summary and coverage, its sessions and items, and its row for every entry of the active plan. */
data class DayDetail(
    val summary: DaySummary,
    val coverage: DayCoverage,
    val sessions: List<AutomationSessionRow>,
    val items: List<AutomationItemView>,
    val evaluations: List<DayVerdict>,
)
