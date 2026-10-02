package com.trenching.bot.replay.domain

import com.trenching.bot.papertrading.domain.*
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.strategy.domain.*
import com.trenching.bot.walletactivity.domain.NormalizationOutcome

data class SimulationReport(
    val runId: String,
    val engineVersion: String,
    val datasetChecksum: String,
    val configurationHash: String,
    val configuration: SimulationConfig,
    val ingestion: List<NormalizationOutcome>,
    val scenarios: List<ScenarioResult>,
    val resultChecksum: String,
)

data class ScenarioResult(
    val intent: TradeIntent,
    val outcome: FillOutcome,
    val position: Position?,
    val exitUnavailable: RejectionReason?,
)
