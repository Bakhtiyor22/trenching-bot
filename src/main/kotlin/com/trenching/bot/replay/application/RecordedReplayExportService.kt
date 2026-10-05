package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.ReplayLimits
import com.trenching.bot.marketdata.MarketHistoryDecodeResult
import com.trenching.bot.marketdata.MarketObservationExclusion
import com.trenching.bot.marketdata.RecordedMarketHistory
import com.trenching.bot.replay.domain.RecordedReplayExport
import com.trenching.bot.replay.domain.RecordedReplayExportRequest
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.ReplayMarketWindowPlan
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetFiles
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import com.trenching.bot.walletactivity.RecordedWalletHistory
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper

/**
 * Exports a recorded wallet capture and the market capture planned from it as one canonical v1 dataset. Both captures are
 * verified offline. The market capture must hold exactly the planned windows, each complete and free of detail gaps and
 * in-window exclusions, so a missing trade can never read as an absence of trades. The JSON is loaded back before it is
 * written.
 */
class RecordedReplayExportService(
    walletHistory: RecordedWalletHistory,
    private val marketHistory: RecordedMarketHistory,
    private val mapper: ObjectMapper,
    planner: ReplayMarketWindowPlanner = ReplayMarketWindowPlanner(),
) {
    private val log = LoggerFactory.getLogger(RecordedReplayExportService::class.java)
    private val planning = RecordedWalletPlanning(walletHistory, planner)

    fun export(request: RecordedReplayExportRequest): RecordedReplayExport {
        val planned = planning.plan(request.walletCaptureDirectory)
        val market = marketHistory.decode(request.marketCaptureDirectory)
        requireMatching(planned.plan, market)
        val dataset = ReplayDataset(SCHEMA_VERSION, planned.history.wallet, planned.history.transactions, market.observations)
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

    private fun requireMatching(plan: ReplayMarketWindowPlan, market: MarketHistoryDecodeResult) {
        if (market.windowParameters != plan.parameters || market.windows.map { it.window }.toSet() != plan.windows.toSet()) {
            throw CaptureValidationException("MARKET_CAPTURE_MISMATCH")
        }
        if (!market.complete || market.windows.any { !it.yieldsObservations }) throw CaptureValidationException("MARKET_CAPTURE_INCOMPLETE")
        if (market.exclusions.any { (reason, count) -> reason !in NON_BLOCKING_EXCLUSIONS && count > 0 }) {
            throw CaptureValidationException("MARKET_CAPTURE_EXCLUSIONS")
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1

        /** Events no replay window needs. Every other exclusion is a trade a window lost, which could move a fill. */
        val NON_BLOCKING_EXCLUSIONS = setOf(MarketObservationExclusion.OUT_OF_WINDOW, MarketObservationExclusion.QUOTE_MINT)
    }
}
