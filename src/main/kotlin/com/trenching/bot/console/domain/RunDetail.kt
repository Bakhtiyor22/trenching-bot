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

/** A run's detail with where its dataset came from, for the run page. */
data class RunDetailView(val detail: RunDetail, val source: DatasetSource)

/** One entry offset of one source buy; [selectedObservation] is the observation its fill used. */
data class RunScenario(
    val intent: TradeIntent,
    val outcome: FillOutcome,
    val selectedObservation: MarketObservation?,
    val position: Position?,
    val exitUnavailable: RejectionReason?,
    val timeline: ScenarioTimeline,
)

/**
 * Block times of a scenario's milestones: the tracked wallet's buy, the dip trigger, the entry fill, the exit trigger and the
 * exit fill. Each is null when the milestone did not happen or its time was not recorded.
 */
data class ScenarioTimeline(
    val firstBuyAt: Instant?,
    val dipAt: Instant?,
    val entryAt: Instant?,
    val exitTriggerAt: Instant?,
    val exitAt: Instant?,
)
