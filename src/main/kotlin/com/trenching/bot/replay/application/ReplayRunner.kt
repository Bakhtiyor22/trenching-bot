package com.trenching.bot.replay.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.marketdata.domain.OrderedReferenceRates
import com.trenching.bot.papertrading.application.FillModel
import com.trenching.bot.papertrading.domain.*
import com.trenching.bot.portfolio.application.PositionService
import com.trenching.bot.portfolio.domain.*
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import com.trenching.bot.replay.domain.*
import com.trenching.bot.strategy.application.DipEntryStrategy
import com.trenching.bot.strategy.domain.*
import com.trenching.bot.walletactivity.application.WalletNormalizer
import com.trenching.bot.walletactivity.domain.*

/** Replays a v3, v4 or v5 dataset under the dip-entry strategy: one cycle per mint, from the tracked wallet's first buy of it. */
class ReplayRunner(
    private val normalizer: WalletNormalizer = WalletNormalizer(),
    private val strategy: DipEntryStrategy = DipEntryStrategy(),
    private val fills: FillModel = FillModel(),
    private val positions: PositionService = PositionService(),
) {
    fun run(dataset: ReplayDataset, config: SimulationConfig): SimulationReport {
        require(dataset.schemaVersion in ReplayDataset.SUPPORTED_SCHEMA_VERSIONS) { "Unsupported replay schema" }
        val transactions = OrderedWalletTransactions.canonical(dataset.transactions)
        val source = OrderedMarketData(dataset.observations)
        val rates = OrderedReferenceRates(dataset.referenceRates)
        val datasetHash = DatasetFingerprint.checksum(dataset)
        val configHash = configurationHash(config)
        val runId = StableHash.of(listOf(ENGINE_VERSION, datasetHash, configHash))
        val normalized = transactions.map { normalizer.normalize(it, dataset.trackedWallet, config.quoteMint) }
        val plan = strategy.plan(normalized.filterIsInstance<NormalizationOutcome.Accepted>().map { it.trade },
            runId, configHash, config, source, rates)
        val laterBuys = plan.laterBuys.toSet()
        val ingestion = normalized.map { outcome ->
            if (outcome is NormalizationOutcome.Accepted && outcome.trade.id in laterBuys) {
                NormalizationOutcome.Ignored(outcome.trade.id, IngestionReason.LATER_BUY_SAME_MINT)
            } else outcome
        }
        val scenarios = plan.intents.map { intent ->
            when (val outcome = fills.fill(intent, source, config)) {
                is FillOutcome.Rejected -> ScenarioResult(intent, outcome, null, null)
                is FillOutcome.Filled -> {
                    val opened = positions.open(intent, outcome.fill, config)
                    when (val closed = positions.close(opened, intent, source, config)) {
                        is CloseOutcome.Closed -> ScenarioResult(intent, outcome, closed.position, null)
                        is CloseOutcome.Unavailable -> ScenarioResult(intent, outcome, closed.position, closed.reason)
                        is CloseOutcome.AlreadyClosed -> ScenarioResult(intent, outcome, closed.position, null)
                    }
                }
            }
        }
        return SimulationReport(runId, ENGINE_VERSION, datasetHash, configHash, config, ingestion, scenarios,
            StableHash.of(resultParts(runId, scenarios)))
    }

    private fun resultParts(runId: String, scenarios: List<ScenarioResult>): List<String> {
        val parts = mutableListOf(runId)
        scenarios.forEach { scenario ->
            val intent = scenario.intent
            parts.addAll(listOf(intent.id, intent.referencePrice.toPlainString(), intent.dipPrice.toPlainString(), intent.watch.name))
            intent.trigger?.let { parts.addAll(listOf(it.observationId, it.slot.value.toString(), it.marketCapUsd.toPlainString(), it.rate.toPlainString())) }
            when (val outcome = scenario.outcome) {
                is FillOutcome.Rejected -> parts.add(outcome.reason.name)
                is FillOutcome.Filled -> {
                    val fill = outcome.fill
                    parts.addAll(listOf(fill.observationId, fill.actualSlot.value.toString(),
                        fill.executionPrice.toPlainString(), fill.quantity.toPlainString(), fill.fee.toPlainString(),
                        fill.feeBps.toString(), fill.priorityFee.toPlainString(), fill.priceImpactBps.toString(),
                        fill.costBasis.toPlainString(), scenario.position?.state?.name ?: "",
                        scenario.exitUnavailable?.name ?: ""))
                    scenario.position?.let { position ->
                        parts.addAll(listOf(position.takeProfitPrice.toPlainString(), position.stopLossPrice.toPlainString()))
                        position.exitTrigger?.let { parts.addAll(listOf(it.observationId, it.slot.value.toString(), it.reason.name)) }
                        position.close?.let { close ->
                            parts.addAll(listOf(close.observationId, close.slot.value.toString(),
                                close.executionPrice.toPlainString(), close.grossProceeds.toPlainString(), close.fee.toPlainString(),
                                close.feeBps.toString(), close.transactionCost.toPlainString(), close.priceImpactBps.toString(),
                                close.netProceeds.toPlainString(), close.realizedPnl.toPlainString(), close.reason.name))
                        }
                    }
                }
            }
        }
        return parts
    }

    companion object {
        /** dip-paper-v2: dip-paper-v1's triggers with bonding-curve execution on schema v3 datasets. */
        const val ENGINE_VERSION = "dip-paper-v2"

        /** Fills trade against the fill trade's post-trade curve, outputs rounded down and fees rounded up. */
        private const val CURVE_RULE = "curve-post-trade-floor-fees-ceil"

        /** The hash a report of [config] carries; it depends on the configuration and the engine's rules only, never on a dataset. */
        fun configurationHash(config: SimulationConfig): String =
            StableHash.of(listOf(config.strategyName, config.strategyVersion, config.notional.toPlainString(),
                config.maxAgeSlots.toString(), config.dipBps.toString(), config.takeProfitBps.toString(), config.stopLossBps.toString(),
                config.minMarketCapUsd.toPlainString(), config.horizonSlots.toString(), "latencySlots=${config.latencySlots}",
                "transactionCostSol=${config.transactionCostSol.toPlainString()}", config.quoteMint.value,
                "scale=${DecimalPolicy.SCALE}", "priceScale=${DecimalPolicy.PRICE_SCALE}", DecimalPolicy.ROUNDING.name,
                "supply=${PumpFunLayout.TOKEN_SUPPLY}", "fills=first-eligible-after-latency", CURVE_RULE, "confirmed-signer-buys"))
    }
}
