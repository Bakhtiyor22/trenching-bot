package com.trenching.bot.console.automation.api.dto

import java.time.Instant

/** [resumeBefore] is set while discovery works through a backlog larger than one scan: the next scan continues from it. */
data class AutomationWatermarkResponse(
    val slot: Long,
    val signature: String,
    val instructionIndex: Int,
    val innerIndex: Int,
    val updatedAt: Instant,
    val resumeBefore: String?,
)

/** [pollFrom] and [pollTo] are local `HH:mm` times in [zone]; [requestsLast24h] counts every provider request of the rolling day. */
data class AutomationStatusResponse(
    val enabled: Boolean,
    val captureEnabled: Boolean,
    val wallet: String?,
    val sweepPlanId: String,
    val zone: String,
    val pollFrom: String,
    val pollTo: String,
    val inPollWindow: Boolean,
    val watermark: AutomationWatermarkResponse?,
    val requestsLast24h: Long,
    val maxRequestsPer24h: Long,
    val rpcConfigured: Boolean,
    val lastTickAt: Instant?,
    val nextTickAt: Instant?,
    val lastDiscoveryAt: Instant?,
    val discoveryProblem: String?,
    val openSessionId: String?,
)

/**
 * [itemCounts] has every item state, zeros included; [requestsUsed] sums the provider requests of its items' captures;
 * [bestStatus] and [bestLabel] are null until the session is evaluated.
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
    val bestStatus: String?,
    val bestLabel: String?,
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

/**
 * One sweep entry's score over the window of [sessionId]: [windowSessionIds] oldest first buy first, ending with the session.
 * P&L values are exact SOL decimal strings at scale 12, summed over the signals.
 */
data class AutomationEvaluationResponse(
    val sessionId: String,
    val planId: String,
    val label: String,
    val windowSessionIds: List<String>,
    val signals: Int,
    val scored: Int,
    val unscored: Int,
    val realizedPnl: String,
    val worstCasePnl: String,
    val status: String,
    val evaluatedAt: Instant,
)

data class AutomationEvaluationPageResponse(val items: List<AutomationEvaluationResponse>, val page: Int, val size: Int, val total: Long)

data class AutomationSessionDetailResponse(
    val session: AutomationSessionResponse,
    val items: List<AutomationItemResponse>,
    val evaluations: List<AutomationEvaluationResponse>,
)
