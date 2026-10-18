package com.trenching.bot.console.domain

import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.strategy.domain.TradeIntent
import java.time.Instant

data class RunDetail(val run: RunSummary, val config: SimulationConfig, val scenarios: List<RunScenario>)

/** A run's detail with all its stored observations, from one read of them. */
data class RunObservations(val detail: RunDetail, val observations: List<MarketObservation>)

/** A run's detail as the run page shows it: each scenario with its timeline, and the dataset, capture and case it was run on. */
data class SourcedRunDetail(val run: RunSummary, val config: SimulationConfig, val scenarios: List<TimedRunScenario>, val source: DatasetSource)

data class TimedRunScenario(val scenario: RunScenario, val timeline: ScenarioTimeline)

/** One entry offset of one source buy; [selectedObservation] is the observation its fill used. */
data class RunScenario(
    val intent: TradeIntent,
    val outcome: FillOutcome,
    val selectedObservation: MarketObservation?,
    val position: Position?,
    val exitUnavailable: RejectionReason?,
)

/** The block times of a scenario's stages; each is null when its stage did not happen or its block time is not stored. */
data class ScenarioTimeline(
    val firstBuyAt: Instant?,
    val dipAt: Instant?,
    val entryAt: Instant?,
    val exitTriggerAt: Instant?,
    val exitAt: Instant?,
)
