package com.trenching.bot.marketdata.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.*
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.marketdata.infrastructure.capture.MarketCaptureFormat
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import com.trenching.bot.marketdata.infrastructure.capture.MarketWindowRecords
import com.trenching.bot.marketdata.infrastructure.extraction.PumpFunTradeObservationExtractor
import com.trenching.bot.marketdata.infrastructure.extraction.TradeExtraction
import java.nio.file.Path
import org.slf4j.LoggerFactory

internal class RecordedMarketHistoryService(
    private val store: MarketHistoryArtifactStore,
    private val extractor: PumpFunTradeObservationExtractor,
) : RecordedMarketHistory {
    private val log = LoggerFactory.getLogger(RecordedMarketHistoryService::class.java)

    override fun inspect(directory: Path): MarketHistoryCaptureResult = store.inspect(directory)

    override fun decode(directory: Path): MarketHistoryDecodeResult {
        val verified = store.inspect(directory)
        if (!verified.manifest.acquisitionComplete) throw CaptureValidationException("CAPTURE_INCOMPLETE")
        val outcomes = verified.manifest.records.associate { it.signature to it.outcome }
        val usable = verified.manifest.windows.filter { yieldsObservations(it, outcomes) }.map(MarketWindowRecords::window)
        val extractions = mutableMapOf<String, List<TradeExtraction>>()
        val manifest = store.loadIfPresent(directory) { record, transaction ->
            extractions[record.signature] = extractor.extract(transaction, TransactionSignature.of(record.signature), Slot.of(record.slot)) { mint, slot ->
                usable.any { it.contains(mint, slot) }
            }
        }?.takeIf { it == verified.manifest } ?: throw CaptureValidationException("CAPTURE_CHANGED")
        val observations = OrderedMarketData.canonical(extractions.values.flatten().filterIsInstance<TradeExtraction.Observed>().map { it.observation })
        val excluded = extractions.values.flatten().filterIsInstance<TradeExtraction.Excluded>()
        val windows = manifest.windows.map { record ->
            val window = MarketWindowRecords.window(record)
            MarketWindowDecodeRecord(window, record.coverage, record.selected, detailGaps(record, outcomes), yieldsObservations(record, outcomes),
                observations.count { window.contains(it.pair.base, it.slot) })
        }
        val transactions = manifest.records.map { record ->
            val extracted = extractions[record.signature].orEmpty()
            MarketTransactionDecodeRecord(TransactionSignature.of(record.signature), Slot.of(record.slot), record.outcome,
                extracted.count { it is TradeExtraction.Observed }, extracted.count { it is TradeExtraction.Excluded })
        }
        val exclusions = MarketObservationExclusion.entries.associateWith { reason -> excluded.count { it.reason == reason } }
        log.info("market_history_decode finished windows={} usableWindows={} transactions={} observations={} exclusions={}",
            windows.size, usable.size, transactions.size, observations.size, excluded.size)
        return MarketHistoryDecodeResult(verified.directory, manifest.complete, MarketObservationExtractorVersion.PUMPFUN_BONDING_CURVE_TRADE_V2,
            manifest.windowParameters, windows, transactions, observations, exclusions)
    }

    override fun inspectCases(directory: Path): MarketCaseInventory {
        val manifest = store.loadManifest(directory) ?: throw CaptureValidationException("MANIFEST_MISSING")
        val records = manifest.records.associateBy { it.signature }
        val progress = manifest.windows.map { window -> progress(window, records) }
        return MarketCaseInventory(directory.toAbsolutePath().normalize(), progress)
    }

    override fun decodeCase(directory: Path, caseId: MarketCaseId): MarketCaseDecodeResult {
        val extractions = linkedMapOf<String, List<TradeExtraction>>()
        var selectedWindow: MarketWindowRecord? = null
        val manifest = store.loadCaseIfPresent(directory, caseId) { record, transaction, windowRecord ->
            selectedWindow = windowRecord
            val window = MarketWindowRecords.window(windowRecord)
            extractions[record.signature] = extractor.extract(transaction, TransactionSignature.of(record.signature), Slot.of(record.slot)) { mint, slot ->
                window.contains(mint, slot)
            }
        } ?: throw CaptureValidationException("MANIFEST_MISSING")
        val record = selectedWindow ?: manifest.windows.singleOrNull { MarketCaseId.from(MarketWindowRecords.window(it)) == caseId }
            ?: throw CaptureValidationException("MARKET_CASE_NOT_FOUND")
        val current = progress(record, manifest.records.associateBy { it.signature })
        val window = MarketWindowRecords.window(record)
        val observations = OrderedMarketData.canonical(extractions.values.flatten()
            .filterIsInstance<TradeExtraction.Observed>().map { it.observation })
        val excluded = extractions.values.flatten().filterIsInstance<TradeExtraction.Excluded>()
        val exclusions = MarketObservationExclusion.entries.associateWith { reason -> excluded.count { it.reason == reason } }
        val blockers = exclusions.filter { (reason, count) -> reason !in NON_BLOCKING_EXCLUSIONS && count > 0 }
        val blocker = when {
            current.blocker != null -> current.blocker
            blockers.isNotEmpty() -> "MARKET_CAPTURE_EXCLUSIONS"
            observations.isEmpty() -> "MARKET_NO_OBSERVATIONS"
            else -> null
        }
        return MarketCaseDecodeResult(current, manifest.windowParameters,
            MarketObservationExtractorVersion.PUMPFUN_BONDING_CURVE_TRADE_V2, observations, exclusions,
            current.unresolved, blocker == null, blocker)
    }

    private fun progress(window: MarketWindowRecord, records: Map<String, MarketTransactionRecord>): MarketCaseProgress {
        val selected = window.selected
        val captured = window.selection.count { records[it.signature]?.outcome == MarketTransactionOutcome.CAPTURED }
        val unresolved = selected - captured
        val unresolvedOutcomes = window.selection.mapNotNull { records[it.signature]?.outcome }
            .filter { it in MarketCaptureFormat.UNRESOLVED_OUTCOMES }
        val stage = when {
            window.coverage == MarketWindowCoverage.PENDING -> MarketCaseStage.DISCOVERY_PENDING
            window.coverage != MarketWindowCoverage.COMPLETE -> MarketCaseStage.DISCOVERY_INCOMPLETE
            unresolvedOutcomes.isNotEmpty() -> MarketCaseStage.DETAILS_PENDING
            unresolved > 0 -> MarketCaseStage.DETAILS_INCOMPLETE
            else -> MarketCaseStage.READY_TO_VALIDATE
        }
        val blocker = when (stage) {
            MarketCaseStage.DISCOVERY_PENDING -> "MARKET_DISCOVERY_PENDING"
            MarketCaseStage.DISCOVERY_INCOMPLETE -> "MARKET_DISCOVERY_INCOMPLETE"
            MarketCaseStage.DETAILS_PENDING -> "MARKET_DETAILS_PENDING"
            MarketCaseStage.DETAILS_INCOMPLETE -> "MARKET_DETAIL_GAPS"
            MarketCaseStage.READY_TO_VALIDATE -> null
        }
        return MarketCaseProgress(MarketCaseId.from(MarketWindowRecords.window(window)), MarketWindowRecords.window(window),
            window.coverage, stage, selected, captured, unresolved, blocker)
    }

    private companion object {
        val NON_BLOCKING_EXCLUSIONS = setOf(MarketObservationExclusion.OUT_OF_WINDOW, MarketObservationExclusion.QUOTE_MINT)
    }

    private fun detailGaps(window: MarketWindowRecord, outcomes: Map<String, MarketTransactionOutcome>): Int =
        window.selection.count { outcomes[it.signature] != MarketTransactionOutcome.CAPTURED }

    /** A missing in-window transaction could hide an earlier trade, so its whole window yields nothing. */
    private fun yieldsObservations(window: MarketWindowRecord, outcomes: Map<String, MarketTransactionOutcome>): Boolean =
        window.coverage == MarketWindowCoverage.COMPLETE && detailGaps(window, outcomes) == 0
}
