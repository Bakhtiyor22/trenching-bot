package com.trenching.bot.console.application

import com.trenching.bot.common.PageResult
import com.trenching.bot.console.domain.RunDetail
import com.trenching.bot.console.domain.RunEvent
import com.trenching.bot.console.domain.RunOrder
import com.trenching.bot.console.domain.RunPosition
import com.trenching.bot.console.domain.RunObservations
import com.trenching.bot.console.domain.RunScenario
import com.trenching.bot.console.report.RunReportReader
import com.trenching.bot.marketdata.MarketObservationStore
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.orderId
import com.trenching.bot.portfolio.PositionStore
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.replay.domain.SimulationRunNotFoundException
import com.trenching.bot.strategy.TradeIntentStore
import com.trenching.bot.walletactivity.WalletEventStore
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import com.trenching.bot.walletactivity.domain.WalletEventIdentity
import org.springframework.stereotype.Service

/**
 * Read views of stored runs, composed from the module store ports. Runs are immutable, so the
 * separate store reads of one view cannot disagree. Each store read is bounded by `ReplayLimits`.
 */
@Service
class ConsoleService(
    private val runs: SimulationRuns,
    private val reports: RunReportReader,
    private val events: WalletEventStore,
    private val observations: MarketObservationStore,
    private val intents: TradeIntentStore,
    private val orders: PaperOrderStore,
    private val positions: PositionStore,
) {
    fun page(page: Int, size: Int, datasetId: String?): PageResult<RunSummary> {
        ConsolePaging.require(page, size)
        return runs.page(page, size, datasetId?.takeIf { it.isNotBlank() })
    }

    fun detail(runId: String): RunDetail = detailWithObservations(runId).detail

    fun detailWithObservations(runId: String): RunObservations {
        val run = requireRun(runId)
        val stored = observations.observations(runId)
        return RunObservations(detail(run, stored), stored)
    }

    fun events(runId: String): List<RunEvent> {
        requireRun(runId)
        val accepted = events.acceptedTrades(runId).associate { it.id to NormalizationOutcome.Accepted(it) }
        val ignored = events.ignored(runId).associateBy { it.sourceEventId }
        return events.transactions(runId).map { transaction ->
            val eventId = WalletEventIdentity.of(transaction.signature, transaction.instructionIndex)
            val outcome = accepted[eventId] ?: ignored[eventId]
            RunEvent(eventId, transaction, checkNotNull(outcome) { "Missing ingestion outcome runId=$runId eventId=$eventId" })
        }
    }

    fun observations(runId: String): List<MarketObservation> {
        requireRun(runId)
        return observations.observations(runId)
    }

    fun orders(runId: String): List<RunOrder> {
        requireRun(runId)
        val intentsById = intents.intents(runId).associateBy { it.id }
        return orders.outcomes(runId).map { outcome ->
            val orderId = outcome.orderId
            RunOrder(checkNotNull(intentsById[orderId]) { "Missing trade intent runId=$runId orderId=$orderId" }, outcome)
        }
    }

    fun positions(runId: String): List<RunPosition> {
        requireRun(runId)
        return positions.storedPositions(runId).map { RunPosition(it.position, it.exitUnavailable) }
    }

    fun report(runId: String): String = runs.report(runId)

    private fun detail(run: RunSummary, stored: List<MarketObservation>): RunDetail {
        val runId = run.id
        val facts = reports.read(runs.report(runId))
        val outcomes = orders.outcomes(runId).associateBy { it.orderId }
        val positionsByIntent = positions.storedPositions(runId).associateBy { it.position.intentId }
        val observationsById = stored.associateBy { it.id }
        val buyTimes = events.acceptedTrades(runId).associate { it.id to it.blockTime }
        val scenarios = intents.intents(runId).map { intent ->
            val outcome = checkNotNull(outcomes[intent.id]) { "Missing paper order runId=$runId intentId=${intent.id}" }
            val selected = (outcome as? FillOutcome.Filled)?.let { observationsById[it.fill.observationId] }
            val storedPosition = positionsByIntent[intent.id]
            RunScenario(intent, outcome, selected, storedPosition?.position, storedPosition?.exitUnavailable,
                ScenarioTimelines.of(intent, outcome, storedPosition?.position, buyTimes, observationsById))
        }
        return RunDetail(run, facts.config, scenarios)
    }

    private fun requireRun(runId: String): RunSummary = runs.find(runId) ?: throw SimulationRunNotFoundException(runId)

}
