package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.ReplayLimits
import com.trenching.bot.marketdata.MarketCaseId
import com.trenching.bot.marketdata.RecordedMarketHistory
import com.trenching.bot.marketdata.RecordedReferenceRates
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.replay.CaptureCaseExport
import com.trenching.bot.replay.CaptureCaseExportRequest
import com.trenching.bot.replay.domain.DatasetTrades
import com.trenching.bot.replay.domain.MarketCoverage
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetFiles
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import com.trenching.bot.walletactivity.RecordedWalletHistory
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper

internal class RecordedCaseReplayExportService(
    walletHistory: RecordedWalletHistory,
    private val markets: RecordedMarketHistory,
    private val referenceRates: RecordedReferenceRates,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(RecordedCaseReplayExportService::class.java)
    private val planning = RecordedWalletPlanning(walletHistory)

    fun export(request: CaptureCaseExportRequest): CaptureCaseExport {
        val planned = planning.plan(request.walletCaptureDirectory)
        val window = planned.plan.windows.singleOrNull { MarketCaseId.from(it) == request.caseId }
            ?: throw CaptureValidationException("MARKET_CASE_NOT_FOUND")
        val anchor = planning.anchor(planned, window)
        if (request.wallet != planned.history.wallet || request.mint != window.baseMint || request.anchorSignature != anchor.signature ||
            request.anchorSlot != anchor.slot.value || request.instructionIndex != anchor.instructionIndex ||
            request.fromSlot != window.fromSlot.value || request.toSlot != window.toSlot.value ||
            request.windowParameters != planned.plan.parameters) {
            throw CaptureValidationException("MARKET_CASE_IDENTITY_MISMATCH")
        }
        val sameTransaction = planned.history.transactions.filter { it.signature == anchor.signature }
        if (sameTransaction.size != 1) throw CaptureValidationException("CASE_ANCHOR_MULTITRADE_TRANSACTION")
        val walletTransaction = sameTransaction.single().takeIf { it.instructionIndex == anchor.instructionIndex }
            ?: throw CaptureValidationException("MARKET_CAPTURE_MISMATCH")
        val decoded = markets.decodeCase(request.marketCaptureDirectory, request.caseId)
        if (!decoded.ready) throw CaptureValidationException(decoded.blocker ?: "MARKET_CASE_INCOMPLETE")
        if (decoded.progress.window != window || decoded.windowParameters != planned.plan.parameters) {
            throw CaptureValidationException("MARKET_CAPTURE_MISMATCH")
        }
        if (!DatasetTrades.allPresent(decoded.observations)) {
            log.warn("recorded_case_export refused reason={} caseId={}", DatasetTrades.FIELDS_MISSING, request.caseId.value)
            throw CaptureValidationException(DatasetTrades.FIELDS_MISSING)
        }
        val caseRates = rates(request.ratesCaptureDirectory, decoded.observations)
        val coverage = MarketCoverage.of(window.pair, window.fromSlot, decoded.progress.observedThrough)
        val dataset = ReplayDataset(ReplayDataset.CURRENT_SCHEMA_VERSION, planned.history.wallet, listOf(walletTransaction),
            decoded.observations, caseRates, listOf(coverage))
        if (dataset.transactions.size > ReplayLimits.MAX_INPUT_RECORDS || dataset.observations.size > ReplayLimits.MAX_INPUT_RECORDS) {
            throw CaptureValidationException("DATASET_BOUNDS")
        }
        val json = ReplayDatasetWriter(mapper).write(dataset)
        val loaded = ReplayDatasetLoader(mapper).load(json.byteInputStream(Charsets.UTF_8))
        val written = ReplayDatasetFiles.writeOnce(request.output, json)
        val result = CaptureCaseExport(request.output, DatasetFingerprint.checksum(loaded), request.caseId,
            planned.history.wallet, window.baseMint, anchor.signature, anchor.slot.value, anchor.instructionIndex, window.fromSlot.value,
            window.toSlot.value, planned.plan.parameters, decoded.extractor, loaded.transactions.size, loaded.observations.size, written)
        log.info("recorded_case_export finished caseId={} checksum={} transactions={} observations={} written={}",
            result.caseId.value, result.checksum, result.transactions, result.observations, result.written)
        return result
    }

    private fun rates(directory: java.nio.file.Path, observations: List<com.trenching.bot.marketdata.domain.MarketObservation>): List<ReferenceRate> {
        val byMinute = referenceRates.rates(directory).associateBy { it.minute }
        val minutes = observations.map { ReferenceRate.minuteOf(it.observedTime) }.toSortedSet()
        if (minutes.any { it !in byMinute }) throw CaptureValidationException("REFERENCE_RATES_INCOMPLETE")
        return minutes.map { byMinute.getValue(it) }
    }
}
