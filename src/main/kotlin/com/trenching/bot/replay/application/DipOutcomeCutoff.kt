package com.trenching.bot.replay.application

import com.trenching.bot.common.Slot
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.CutoffDecision
import com.trenching.bot.marketdata.CutoffIdentity
import com.trenching.bot.marketdata.MarketCaptureCutoff
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.RateLookup
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.SolUsdtRateSource
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.replay.domain.DatasetCoverage
import com.trenching.bot.replay.domain.MarketCoverage
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.ScenarioDecision
import com.trenching.bot.replay.domain.ScenarioResult
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.walletactivity.domain.WalletEventIdentity
import com.trenching.bot.walletactivity.domain.WalletTransaction
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * Decides a dip-entry market window by replaying its captured prefix with the unchanged [ReplayRunner] under
 * [SimulationConfig.mvp], the configuration qualification replays. The prefix is cut before the first trade of a minute
 * without a SOL/USDT rate, so a trigger is never replayed without its rate. Rates are looked up once per minute for one
 * capture invocation and never stored: export and qualification replay with the recorded rates, which re-checks every decision.
 *
 * [anchors] holds, per planned window, the wallet transactions of its anchor buy that trade its mint.
 */
internal class DipOutcomeCutoff(
    private val trackedWallet: WalletAddress,
    private val anchors: Map<MarketCaptureWindow, List<WalletTransaction>>,
    private val rates: SolUsdtRateSource,
    private val runner: ReplayRunner = ReplayRunner(),
) : MarketCaptureCutoff {
    private val log = LoggerFactory.getLogger(DipOutcomeCutoff::class.java)
    private val knownRates = HashMap<Instant, ReferenceRate>()

    override fun evaluate(window: MarketCaptureWindow, observations: List<MarketObservation>, observedThrough: Slot): CutoffDecision {
        val transactions = checkNotNull(anchors[window]) { "Window is not planned mint=${window.baseMint.value} fromSlot=${window.fromSlot.value}" }
        lookUpRates(observations.mapTo(HashSet()) { ReferenceRate.minuteOf(it.observedTime) } - knownRates.keys)
        val through = ratedThrough(observations, observedThrough)
        val prefix = observations.filter { it.slot <= through }
        val rates = prefix.map { ReferenceRate.minuteOf(it.observedTime) }.toSortedSet().map(knownRates::getValue)
        val dataset = ReplayDataset(ReplayDataset.CURRENT_SCHEMA_VERSION, trackedWallet, transactions, prefix, rates,
            listOf(MarketCoverage.of(window.pair, window.fromSlot, through)))
        return try {
            decision(window, dataset)
        } catch (exception: RuntimeException) {
            log.error("market_capture cutoff replay_failed mint={} observedThrough={}", window.baseMint.value, through.value, exception)
            CutoffDecision.Undecided
        }
    }

    /**
     * Unresolved minutes are asked for again with the next evaluation's new ones, so a passing rate outage costs one more
     * request rather than the rest of the window.
     */
    private fun lookUpRates(minutes: Set<Instant>) {
        if (minutes.isEmpty()) return
        try {
            rates.minuteRates(minutes).forEach { (minute, lookup) ->
                if (lookup is RateLookup.Available) knownRates[minute] = ReferenceRate(minute, lookup.rate, lookup.source)
            }
        } catch (exception: RuntimeException) {
            log.warn("market_capture cutoff rates_unavailable minutes={}", minutes.size, exception)
        }
    }

    /** [observedThrough], or the slot before the first trade whose minute has no rate. */
    private fun ratedThrough(observations: List<MarketObservation>, observedThrough: Slot): Slot =
        observations.filter { ReferenceRate.minuteOf(it.observedTime) !in knownRates }.minOfOrNull { it.slot.value }
            ?.let { Slot.of(it - 1) } ?: observedThrough

    /** Decided only when the window's one cycle starts from its anchor buy and its outcome is decided within the prefix. */
    private fun decision(window: MarketCaptureWindow, dataset: ReplayDataset): CutoffDecision {
        val report = runner.run(dataset, CONFIG)
        val anchorEvents = dataset.transactions.filter { it.signature == window.untilSignature }
            .mapTo(HashSet()) { WalletEventIdentity.of(it.signature, it.instructionIndex) }
        val scenario = report.scenarios.singleOrNull { it.intent.pair == window.pair }
        if (scenario == null || scenario.intent.sourceEventId !in anchorEvents || DatasetCoverage.undecided(report, dataset).isNotEmpty()) {
            return CutoffDecision.Undecided
        }
        return CutoffDecision.Decided(ScenarioDecision.decidedAt(scenario, OrderedMarketData(dataset.observations), CONFIG), outcome(scenario))
    }

    private fun outcome(scenario: ScenarioResult): String = when (val outcome = scenario.outcome) {
        is FillOutcome.Rejected -> outcome.reason.name
        is FillOutcome.Filled -> scenario.position?.close?.reason?.name
            ?: checkNotNull(scenario.exitUnavailable) { "Open position has no reason intentId=${scenario.intent.id}" }.name
    }

    companion object {
        private val CONFIG: SimulationConfig = SimulationConfig.mvp()

        /** The evaluator a decided window records: the engine version and the cutoff configuration's hash. */
        val IDENTITY = CutoffIdentity(ReplayRunner.ENGINE_VERSION, ReplayRunner.configurationHash(CONFIG))
    }
}
