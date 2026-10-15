package com.trenching.bot.marketdata.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.CutoffDecision
import com.trenching.bot.marketdata.CutoffIdentity
import com.trenching.bot.marketdata.MarketCaptureCutoff
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketHistoryManifest
import com.trenching.bot.marketdata.MarketTransactionOutcome
import com.trenching.bot.marketdata.MarketTransactionRecord
import com.trenching.bot.marketdata.MarketWindowCutoff
import com.trenching.bot.marketdata.MarketWindowRecord
import com.trenching.bot.marketdata.domain.MarketCutoffRules
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.marketdata.infrastructure.capture.MarketCaptureFormat
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import com.trenching.bot.marketdata.infrastructure.capture.MarketWindowRecords
import com.trenching.bot.marketdata.infrastructure.extraction.PumpFunTradeObservationExtractor
import com.trenching.bot.marketdata.infrastructure.extraction.TradeExtraction
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Asks a capture's cutoff whether one window's outcome is decided over its slot-complete captured prefix, at most once per
 * prefix end, and applies a decision. The prefix's observations are extracted from the stored artifacts, so a resumed
 * capture sees exactly what an uninterrupted one would.
 */
internal class WindowCutoffEvaluator(
    private val directory: Path,
    private val index: Int,
    private val cutoff: MarketCaptureCutoff,
    private val identity: CutoffIdentity,
    private val store: MarketHistoryArtifactStore,
    private val extractor: PumpFunTradeObservationExtractor,
) {
    private val log = LoggerFactory.getLogger(WindowCutoffEvaluator::class.java)
    private val observationsBySignature = HashMap<String, List<MarketObservation>>()
    private var evaluatedThrough: Long? = null

    fun decided(manifest: MarketHistoryManifest): Boolean = manifest.windows[index].cutoff != null

    /** Returns [manifest] with the window decided, or unchanged when it is undecided or its prefix did not advance. */
    fun evaluate(manifest: MarketHistoryManifest): MarketHistoryManifest {
        val record = manifest.windows[index]
        if (record.cutoff != null) return manifest
        val records = manifest.records.associateBy { it.signature }
        val prefixEnd = MarketCutoffRules.prefixEnd(record, records.mapValues { it.value.outcome })
        if (prefixEnd < 0 || evaluatedThrough?.let { prefixEnd <= it } == true) return manifest
        evaluatedThrough = prefixEnd
        val window = MarketWindowRecords.window(record)
        return when (val decision = cutoff.evaluate(window, observations(record, window, records, prefixEnd), Slot.of(prefixEnd))) {
            CutoffDecision.Undecided -> manifest
            is CutoffDecision.Decided -> apply(manifest, record, decision, prefixEnd)
        }
    }

    private fun apply(manifest: MarketHistoryManifest, record: MarketWindowRecord, decision: CutoffDecision.Decided, prefixEnd: Long): MarketHistoryManifest {
        val slot = decision.slot.value
        if (slot !in (record.fromSlot - 1)..prefixEnd || !MarketCaptureFormat.isCutoffText(decision.outcome)) {
            throw CaptureValidationException(CUTOFF_DECISION)
        }
        val decided = MarketCutoffRules.decided(manifest, index,
            MarketWindowCutoff(slot, decision.outcome, identity.engineVersion, identity.configurationHash))
        val outcomes = decided.records.associate { it.signature to it.outcome }
        log.info("market_capture cutoff mint={} throughSlot={} outcome={} configurationHash={} notNeeded={}", record.baseMint, slot,
            decision.outcome, identity.configurationHash, record.selection.count { outcomes[it.signature] == MarketTransactionOutcome.NOT_NEEDED })
        return decided
    }

    private fun observations(
        record: MarketWindowRecord,
        window: MarketCaptureWindow,
        records: Map<String, MarketTransactionRecord>,
        prefixEnd: Long,
    ): List<MarketObservation> = OrderedMarketData.canonical(record.selection.filter { it.slot <= prefixEnd }.flatMap { listed ->
        observationsBySignature.getOrPut(listed.signature) {
            val captured = records.getValue(listed.signature)
            extractor.extract(store.readTransaction(directory, captured), TransactionSignature.of(captured.signature), Slot.of(captured.slot),
                window::contains).filterIsInstance<TradeExtraction.Observed>().map { it.observation }
        }
    })

    private companion object {
        const val CUTOFF_DECISION = "CUTOFF_DECISION"
    }
}
