package com.trenching.bot.replay.application

import com.trenching.bot.apiusage.ApiUsageContext
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
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.ExitSaleWindow
import com.trenching.bot.replay.domain.DatasetCoverage
import com.trenching.bot.replay.domain.MarketCoverage
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import com.trenching.bot.replay.domain.ScenarioDecision
import com.trenching.bot.replay.domain.ScenarioResult
import com.trenching.bot.replay.domain.SimulationReport
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.walletactivity.domain.WalletEventIdentity
import com.trenching.bot.walletactivity.domain.WalletTransaction
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * Decides a dip-entry market window by replaying its captured prefix with the unchanged [ReplayRunner] under
 * [SimulationConfig.agreed], the configuration qualification replays. A window is decided only once the agreed outcome is
 * decided within the prefix and the recording reaches [ReplayMarketCaptureRequest.MINIMUM_DECIDED_RECORDING] after t0, the
 * anchor buy's block time, so every token keeps at least its first four minutes; a buy without a block time is never decided
 * early. The recording's end is proven by the first trade at or after that moment, or by the block time of the prefix's end,
 * read only when a decision waits on it.
 *
 * The prefix is cut before the first trade of a minute without a SOL/USDT rate, so a trigger is never replayed without its
 * rate. Rates are looked up once per minute for one capture invocation and never stored: export and qualification replay
 * with the recorded rates, which re-checks every decision.
 *
 * [anchors] holds, per planned window, the wallet transactions of its anchor buy that trade its mint. Rate requests are
 * counted in API usage under [usage].
 */
internal class DipOutcomeCutoff(
    private val trackedWallet: WalletAddress,
    private val anchors: Map<MarketCaptureWindow, List<WalletTransaction>>,
    private val rates: SolUsdtRateSource,
    private val runner: ReplayRunner = ReplayRunner(),
    private val usage: ApiUsageContext = ApiUsageContext.UNATTRIBUTED,
) : MarketCaptureCutoff {
    private val log = LoggerFactory.getLogger(DipOutcomeCutoff::class.java)
    private val knownRates = HashMap<Instant, ReferenceRate>()

    override fun evaluate(
        window: MarketCaptureWindow,
        observations: List<MarketObservation>,
        observedThrough: Slot,
        observedThroughTime: () -> Instant?,
    ): CutoffDecision {
        val transactions = checkNotNull(anchors[window]) { "Window is not planned mint=${window.baseMint.value} fromSlot=${window.fromSlot.value}" }
        lookUpRates(observations.mapTo(HashSet()) { ReferenceRate.minuteOf(it.observedTime) } - knownRates.keys)
        val through = ratedThrough(observations, observedThrough)
        val prefix = observations.filter { it.slot <= through }
        val rates = prefix.map { ReferenceRate.minuteOf(it.observedTime) }.toSortedSet().map(knownRates::getValue)
        val dataset = ReplayDataset(ReplayDataset.CURRENT_SCHEMA_VERSION, trackedWallet, transactions, prefix, rates,
            listOf(MarketCoverage.of(window.pair, window.fromSlot, through)))
        // A prefix cut for a missing rate ends before observedThrough, whose time it must not borrow.
        val throughTime = if (through == observedThrough) observedThroughTime else NO_TIME
        return try {
            decision(window, dataset, throughTime)
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
            rates.minuteRates(minutes, usage).forEach { (minute, lookup) ->
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

    /**
     * The prefix is first replayed without its coverage end time. The time is read only when the outcome is decided but no
     * trade proves the four minutes yet, or when an open position waits on a time no trade proves (see [awaitsTime]); the
     * prefix is then replayed again with it as the coverage's `throughTime`.
     */
    private fun decision(window: MarketCaptureWindow, untimed: ReplayDataset, throughTime: () -> Instant?): CutoffDecision {
        val report = runner.run(untimed, CONFIG)
        val scenario = anchored(window, report, untimed) ?: return CutoffDecision.Undecided
        val recordingEnd = scenario.intent.t0?.plus(MINIMUM_RECORDING) ?: return CutoffDecision.Undecided
        val reachedByTrade = untimed.observations.firstOrNull { !it.observedTime.isBefore(recordingEnd) }?.slot
        val decided = DatasetCoverage.undecided(report, untimed).isEmpty()
        if (decided && reachedByTrade != null) return decided(scenario, untimed, reachedByTrade)
        if (!decided && !awaitsTime(scenario, untimed.observations)) return CutoffDecision.Undecided
        val time = throughTime() ?: return CutoffDecision.Undecided
        val coverage = untimed.coverage.single()
        val timed = untimed.copy(coverage = listOf(MarketCoverage.of(coverage.pair, coverage.fromSlot, coverage.throughSlot, time)))
        val timedReport = runner.run(timed, CONFIG)
        val timedScenario = anchored(window, timedReport, timed)
        val reached = reachedByTrade ?: coverage.throughSlot.takeUnless { time.isBefore(recordingEnd) }
        if (timedScenario == null || reached == null || DatasetCoverage.undecided(timedReport, timed).isNotEmpty()) {
            return CutoffDecision.Undecided
        }
        return decided(timedScenario, timed, reached)
    }

    /** The window's one cycle, when it starts from the window's anchor buy. */
    private fun anchored(window: MarketCaptureWindow, report: SimulationReport, dataset: ReplayDataset): ScenarioResult? {
        val anchorEvents = dataset.transactions.filter { it.signature == window.untilSignature }
            .mapTo(HashSet()) { WalletEventIdentity.of(it.signature, it.instructionIndex) }
        return report.scenarios.singleOrNull { it.intent.pair == window.pair }?.takeIf { it.intent.sourceEventId in anchorEvents }
    }

    /**
     * An open position whose exit only the coverage end time can still prove: a time exit whose start no recorded trade
     * reaches, or a take profit or stop loss whose 60 s no recorded trade passes.
     */
    private fun awaitsTime(scenario: ScenarioResult, observations: List<MarketObservation>): Boolean {
        val position = scenario.position?.takeIf { it.close == null } ?: return false
        val trigger = position.exitTrigger
        if (trigger == null) {
            val exitStart = position.exitStart ?: return false
            return observations.none { !it.observedTime.isBefore(exitStart) }
        }
        return when (trigger.reason) {
            CloseReason.TIME_EXIT -> false
            CloseReason.TAKE_PROFIT, CloseReason.STOP_LOSS -> observations.none(ExitSaleWindow.of(trigger, CONFIG)::passedBy)
        }
    }

    /** Decided at the outcome's own slot, or later at [recordingReached], the first slot proving the four minutes. */
    private fun decided(scenario: ScenarioResult, dataset: ReplayDataset, recordingReached: Slot): CutoffDecision {
        val decidedAt = ScenarioDecision.decidedAt(scenario, OrderedMarketData(dataset.observations), CONFIG,
            DatasetCoverage.recordedThrough(dataset)(scenario.intent))
        return CutoffDecision.Decided(maxOf(decidedAt, recordingReached), outcome(scenario))
    }

    private fun outcome(scenario: ScenarioResult): String = when (val outcome = scenario.outcome) {
        is FillOutcome.Rejected -> outcome.reason.name
        is FillOutcome.Filled -> scenario.position?.close?.reason?.name
            ?: checkNotNull(scenario.exitUnavailable) { "Open position has no reason intentId=${scenario.intent.id}" }.name
    }

    companion object {
        private val CONFIG: SimulationConfig = SimulationConfig.agreed()
        private val NO_TIME: () -> Instant? = { null }
        private val MINIMUM_RECORDING = ReplayMarketCaptureRequest.MINIMUM_DECIDED_RECORDING

        /** The evaluator a decided window records: the engine version and the cutoff configuration's hash. */
        val IDENTITY = CutoffIdentity(ReplayRunner.ENGINE_VERSION, ReplayRunner.configurationHash(CONFIG))
    }
}
