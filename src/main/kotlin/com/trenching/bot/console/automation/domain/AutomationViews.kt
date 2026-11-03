package com.trenching.bot.console.automation.domain

import com.trenching.bot.portfolio.domain.PositionState
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

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
)

/**
 * A session with its item counts for every state (zeros included), the provider requests its items' captures used, and its best
 * evaluation under the active plan, if any.
 */
data class AutomationSessionRow(
    val session: AutomationSessionView,
    val itemCounts: Map<AutomationItemState, Long>,
    val requestsUsed: Long,
    val bestStatus: EvaluationStatus?,
    val bestLabel: String?,
)

/** [requestsUsed] is the provider requests of the item's capture session so far, refreshed whenever a capture step ends. */
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

/**
 * One sweep entry's score over the window of [sessionId] (plan Rules 24–26): [windowSessionIds] oldest first buy first, ending
 * with the session itself. P&L is SOL at scale 12, summed over the signals.
 */
data class AutomationEvaluationView(
    val sessionId: String,
    val planId: String,
    val label: String,
    val windowSessionIds: List<String>,
    val signals: Int,
    val scored: Int,
    val unscored: Int,
    val realizedPnl: BigDecimal,
    val worstCasePnl: BigDecimal,
    val status: EvaluationStatus,
    val evaluatedAt: Instant,
)

data class AutomationSessionDetail(
    val session: AutomationSessionRow,
    val items: List<AutomationItemView>,
    val evaluations: List<AutomationEvaluationView>,
)

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
 * Automation's settings and state for the console. [requestsLast24h] counts every provider request of the rolling day, manual
 * captures included, against [maxRequestsPer24h]. [nextTickAt] is null until the scheduler has ticked.
 */
data class AutomationStatus(
    val enabled: Boolean,
    val captureEnabled: Boolean,
    val wallet: String?,
    val sweepPlanId: String,
    val zone: ZoneId,
    val pollFrom: LocalTime,
    val pollTo: LocalTime,
    val inPollWindow: Boolean,
    val watermark: AutomationWatermarkView?,
    val requestsLast24h: Long,
    val maxRequestsPer24h: Long,
    val rpcConfigured: Boolean,
    val lastTickAt: Instant?,
    val nextTickAt: Instant?,
    val lastDiscoveryAt: Instant?,
    val discoveryProblem: WalletActivityUnavailable?,
    val openSessionId: String?,
)
