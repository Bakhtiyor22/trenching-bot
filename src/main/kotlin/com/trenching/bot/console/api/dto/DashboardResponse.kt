package com.trenching.bot.console.api.dto

import java.time.Instant

data class DashboardResponse(
    val generatedAt: Instant,
    val health: HealthResponse,
    val runs: DashboardRunsResponse,
    val scenarios: ScenarioCountsResponse,
    val positions: PositionCountsResponse,
    val realizedPnlByQuoteMint: List<QuoteMintPnlResponse>,
    val rejectionsByReason: List<RejectionCountResponse>,
    val gauges: GaugesResponse,
)

data class HealthResponse(
    val status: String,
    val database: String?,
)

data class DashboardRunsResponse(
    val total: Long,
    val last24h: Long,
    val recent: List<RecentRunResponse>,
)

data class ScenarioCountsResponse(
    val filled: Long,
    val rejected: Long,
)

data class PositionCountsResponse(
    val open: Long,
    val closed: Long,
)

/** [realizedPnl] is an exact decimal string in units of [quoteMint]; never add P&L across mints. */
data class QuoteMintPnlResponse(
    val quoteMint: String,
    val realizedPnl: String,
)

/** [realizedPnl] is an exact decimal string in units of [quoteMint]; never add P&L across mints. */
data class RecentRunResponse(
    val id: String,
    val createdAt: Instant,
    val quoteMint: String,
    val realizedPnl: String,
)

data class RejectionCountResponse(
    val reason: String,
    val count: Long,
)

/** Telemetry, not money: plain JSON numbers, each null when its meter is unavailable. */
data class GaugesResponse(
    val heapUsedBytes: Long?,
    val heapMaxBytes: Long?,
    val uptimeMs: Long?,
    val cpuUsage: Double?,
    val httpRequestCount: Long?,
    val httpMeanLatencyMs: Double?,
)
