package com.trenching.bot.replay.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MarketPair
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.papertrading.application.FillModel
import com.trenching.bot.papertrading.domain.*
import com.trenching.bot.portfolio.application.PositionService
import com.trenching.bot.portfolio.domain.*
import com.trenching.bot.replay.domain.*
import com.trenching.bot.strategy.application.CopyBuyStrategy
import com.trenching.bot.strategy.domain.*
import com.trenching.bot.walletactivity.application.WalletNormalizer
import com.trenching.bot.walletactivity.domain.*

class ReplayRunner(
    private val normalizer: WalletNormalizer = WalletNormalizer(),
    private val strategy: CopyBuyStrategy = CopyBuyStrategy(),
    private val fills: FillModel = FillModel(),
    private val positions: PositionService = PositionService(),
) {
    fun run(dataset: ReplayDataset, config: SimulationConfig): SimulationReport {
        require(dataset.schemaVersion == 1) { "Unsupported replay schema" }
        val transactions = OrderedWalletTransactions.canonical(dataset.transactions)
        val source = OrderedMarketData(dataset.observations)
        val datasetHash = DatasetFingerprint.checksum(dataset)
        val configHash = StableHash.of(listOf(config.strategyName, config.strategyVersion, config.notional.toPlainString(),
            config.feeBps.toString(), config.slippageBps.toString(), config.maxWaitSlots.toString(),
            config.maxAgeSlots.toString(), config.holdingSlots.toString(), config.quoteMint.value,
            "scale=${DecimalPolicy.SCALE}", "priceScale=${DecimalPolicy.PRICE_SCALE}", DecimalPolicy.ROUNDING.name,
            "offsets=" + CopyBuyStrategy.ENTRY_OFFSETS.joinToString(","), "priorityFee=0", "priceImpactBps=0",
            "confirmed-signer-buys"))
        val runId = StableHash.of(listOf(ENGINE_VERSION, datasetHash, configHash))
        val ingestion = transactions.map { normalizer.normalize(it, dataset.trackedWallet, config.quoteMint) }
        val intents = ingestion.filterIsInstance<NormalizationOutcome.Accepted>()
            .flatMap { strategy.intents(it.trade, runId, configHash, config) }
            .sortedWith(compareBy({ it.targetSlot.value }, { it.id }))
        val scenarios = mutableListOf<ScenarioResult>()
        // Intents are processed in target-slot order and every observation's eligibility is the same for each
        // intent of a run, so actual entry slots never decrease within a scenario. Accepted positions per
        // scenario therefore never overlap, and only the latest one can conflict with a new entry.
        val latestPosition = mutableMapOf<Pair<MarketPair, Int>, Position>()
        intents.forEach { intent ->
            val candidate = fills.fill(intent, source, config)
            val existing = latestPosition[intent.pair to intent.entryOffset]
            val conflicting = candidate is FillOutcome.Filled && existing != null &&
                existing.fill.actualSlot <= candidate.fill.actualSlot &&
                (existing.close == null || existing.close.slot >= candidate.fill.actualSlot)
            val outcome = if (conflicting) FillOutcome.Rejected(intent.id, RejectionReason.ALREADY_OPEN) else candidate
            val scenario = when (outcome) {
                is FillOutcome.Rejected -> ScenarioResult(intent, outcome, null, null)
                is FillOutcome.Filled -> {
                    val opened = positions.open(intent, outcome.fill)
                    when (val closed = positions.close(opened, source, config)) {
                        is CloseOutcome.Closed -> ScenarioResult(intent, outcome, closed.position, null)
                        is CloseOutcome.Unavailable -> ScenarioResult(intent, outcome, opened, closed.reason)
                        is CloseOutcome.AlreadyClosed -> ScenarioResult(intent, outcome, closed.position, null)
                    }
                }
            }
            scenario.position?.let { latestPosition[intent.pair to intent.entryOffset] = it }
            scenarios.add(scenario)
        }
        val resultParts = mutableListOf(runId)
        scenarios.forEach { scenario ->
            resultParts.add(scenario.intent.id)
            when (val outcome = scenario.outcome) {
                is FillOutcome.Rejected -> resultParts.add(outcome.reason.name)
                is FillOutcome.Filled -> {
                    val fill = outcome.fill
                    resultParts.addAll(listOf(fill.observationId, fill.actualSlot.value.toString(),
                        fill.executionPrice.toPlainString(), fill.quantity.toPlainString(), fill.fee.toPlainString(),
                        fill.costBasis.toPlainString(), scenario.position?.state?.name ?: "",
                        scenario.exitUnavailable?.name ?: ""))
                    scenario.position?.close?.let { close ->
                        resultParts.addAll(listOf(close.observationId, close.slot.value.toString(),
                            close.executionPrice.toPlainString(), close.grossProceeds.toPlainString(), close.fee.toPlainString(),
                            close.netProceeds.toPlainString(), close.realizedPnl.toPlainString(), close.reason.name))
                    }
                }
            }
        }
        return SimulationReport(runId, ENGINE_VERSION, datasetHash, configHash, config, ingestion, scenarios, StableHash.of(resultParts))
    }

    companion object {
        /** v2: prices kept at [DecimalPolicy.PRICE_SCALE] and non-quote-mint swaps ignored. */
        const val ENGINE_VERSION = "fixture-paper-v2"
    }
}
