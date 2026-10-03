package com.trenching.bot.console.domain

import com.trenching.bot.common.MintAddress
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.replay.domain.RunSummary
import java.math.BigDecimal
import java.time.Instant

data class DashboardSnapshot(
    val generatedAt: Instant,
    val health: HealthSnapshot,
    val totalRuns: Long,
    val runsLast24h: Long,
    val recentRuns: List<RunSummary>,
    val scenarios: ScenarioCounts,
    val positions: PositionCounts,
    val realizedPnlByQuoteMint: List<QuoteMintPnl>,
    val rejections: List<RejectionCount>,
    val gauges: RuntimeGauges,
)

data class ScenarioCounts(val filled: Long, val rejected: Long)

data class PositionCounts(val open: Long, val closed: Long)

/** Realized P&L of every stored run with this quote mint, in units of [quoteMint]. */
data class QuoteMintPnl(val quoteMint: MintAddress, val realizedPnl: BigDecimal)

/** Actuator status codes; [database] is null when no `db` health component exists. */
data class HealthSnapshot(val status: String, val database: String?)

data class RejectionCount(val reason: RejectionReason, val count: Long)

/** Point-in-time JVM/HTTP telemetry; each value is null when its meter is not registered or not available. */
data class RuntimeGauges(
    val heapUsedBytes: Long?,
    val heapMaxBytes: Long?,
    val uptimeMs: Long?,
    val cpuUsage: Double?,
    val httpRequestCount: Long?,
    val httpMeanLatencyMs: Double?,
)
