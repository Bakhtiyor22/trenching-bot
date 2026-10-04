package com.trenching.bot.marketdata.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.*
import com.trenching.bot.marketdata.domain.OrderedMarketData
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
        return MarketHistoryDecodeResult(verified.directory, manifest.complete, MarketObservationExtractorVersion.PUMPFUN_BONDING_CURVE_TRADE_V1,
            manifest.windowParameters, windows, transactions, observations, exclusions)
    }

    private fun detailGaps(window: MarketWindowRecord, outcomes: Map<String, MarketTransactionOutcome>): Int =
        window.selection.count { outcomes[it.signature] != MarketTransactionOutcome.CAPTURED }

    /** A missing in-window transaction could hide an earlier trade, so its whole window yields nothing. */
    private fun yieldsObservations(window: MarketWindowRecord, outcomes: Map<String, MarketTransactionOutcome>): Boolean =
        window.coverage == MarketWindowCoverage.COMPLETE && detailGaps(window, outcomes) == 0
}
