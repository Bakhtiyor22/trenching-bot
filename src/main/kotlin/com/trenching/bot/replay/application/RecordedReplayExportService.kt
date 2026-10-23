package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.ReplayLimits
import com.trenching.bot.marketdata.MarketHistoryDecodeResult
import com.trenching.bot.marketdata.RecordedMarketHistory
import com.trenching.bot.marketdata.RecordedReferenceRates
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.replay.domain.DatasetTrades
import com.trenching.bot.replay.domain.MarketCoverage
import com.trenching.bot.replay.domain.RecordedReplayExport
import com.trenching.bot.replay.domain.RecordedReplayExportRequest
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetFiles
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import com.trenching.bot.walletactivity.RecordedWalletHistory
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper

/**
 * Exports a recorded wallet capture, the market capture planned from it and the rates recorded for that market capture as
 * one canonical v5 dataset. All three are verified offline. The market capture must hold exactly the planned windows, each
 * complete and free of detail gaps and in-window exclusions through its observed end, so a missing trade can never read as
 * an absence of trades; the dataset's coverage records each window from its first slot (its lead before the first buy, or
 * the slot after it for a capture without one) to that end, the window's last slot or its cutoff. Every trade's minute must
 * have a rate. The JSON is loaded back before it is written.
 */
class RecordedReplayExportService(
    walletHistory: RecordedWalletHistory,
    private val marketHistory: RecordedMarketHistory,
    private val referenceRates: RecordedReferenceRates,
    private val mapper: ObjectMapper,
    planner: ReplayMarketWindowPlanner = ReplayMarketWindowPlanner(),
) {
    private val log = LoggerFactory.getLogger(RecordedReplayExportService::class.java)
    private val planning = RecordedWalletPlanning(walletHistory, planner)

    fun export(request: RecordedReplayExportRequest): RecordedReplayExport {
        val history = planning.history(request.walletCaptureDirectory)
        val market = marketHistory.decode(request.marketCaptureDirectory)
        val planned = planning.plan(history, market.windowParameters.leadSlots)
        RecordedMarketVerification.requireMatching(planned.plan, market)
        if (!DatasetTrades.allPresent(market.observations)) {
            log.warn("recorded_replay_export refused reason={} observations={}", DatasetTrades.FIELDS_MISSING, market.observations.size)
            throw CaptureValidationException(DatasetTrades.FIELDS_MISSING)
        }
        val coverage = market.windows.map { MarketCoverage.of(it.window.pair, it.window.fromSlot, it.observedThrough) }
        val dataset = ReplayDataset(ReplayDataset.CURRENT_SCHEMA_VERSION, planned.history.wallet, planned.history.transactions,
            market.observations, rates(request, market), coverage)
        if (dataset.transactions.size > ReplayLimits.MAX_INPUT_RECORDS || dataset.observations.size > ReplayLimits.MAX_INPUT_RECORDS) {
            log.warn("recorded_replay_export refused reason=DATASET_BOUNDS transactions={} observations={} max={}",
                dataset.transactions.size, dataset.observations.size, ReplayLimits.MAX_INPUT_RECORDS)
            throw CaptureValidationException("DATASET_BOUNDS")
        }
        val json = ReplayDatasetWriter(mapper).write(dataset)
        val loaded = ReplayDatasetLoader(mapper).load(json.byteInputStream(Charsets.UTF_8))
        val written = ReplayDatasetFiles.writeOnce(request.output, json)
        val export = RecordedReplayExport(request.output, DatasetFingerprint.checksum(loaded), loaded.trackedWallet,
            planned.plan.sourceBuys, planned.plan.windows.size, loaded.transactions.size, loaded.observations.size, written)
        log.info("recorded_replay_export finished checksum={} transactions={} observations={} windows={} written={}",
            export.checksum, export.transactions, export.observations, export.windows, export.written)
        return export
    }

    /** The recorded rate of each minute with a trade; every such minute must have one. */
    private fun rates(request: RecordedReplayExportRequest, market: MarketHistoryDecodeResult): List<ReferenceRate> {
        val byMinute = referenceRates.rates(request.ratesCaptureDirectory).associateBy { it.minute }
        val minutes = market.observations.map { ReferenceRate.minuteOf(it.observedTime) }.toSortedSet()
        val missing = minutes.count { it !in byMinute }
        if (missing > 0) {
            log.warn("recorded_replay_export refused reason=REFERENCE_RATES_INCOMPLETE missingMinutes={}", missing)
            throw CaptureValidationException("REFERENCE_RATES_INCOMPLETE")
        }
        return minutes.map { byMinute.getValue(it) }
    }
}
