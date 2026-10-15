package com.trenching.bot.replay

import com.trenching.bot.common.Commitment
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.marketdata.MarketHistoryDecodeResult
import com.trenching.bot.marketdata.MarketHistoryManifest
import com.trenching.bot.marketdata.MarketListedSignature
import com.trenching.bot.marketdata.MarketObservationExclusion
import com.trenching.bot.marketdata.MarketObservationExtractorVersion
import com.trenching.bot.marketdata.MarketTransactionDecodeRecord
import com.trenching.bot.marketdata.MarketTransactionOutcome
import com.trenching.bot.marketdata.MarketTransactionRecord
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.MarketWindowDecodeRecord
import com.trenching.bot.marketdata.MarketWindowRecord
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.support.DipSampleV4
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Path
import java.time.Instant

internal object ReplayMarketFixtures {
    private const val SCAN_PAGES = 20
    val PARAMETERS = MarketCaptureWindowParameters(horizonSlots = 4500, maxAgeSlots = 2)
    private val CURVE = CurveState(
        CurveReserves(BigInteger("80123104314"), BigInteger("401756777019139"), BigInteger("50123104314"), BigInteger("121856777019139")),
        feeBps = 95,
        creatorFeeBps = 30,
    )

    /** A manifest whose windows each selected [records]; the first window is [firstCoverage]. */
    fun manifest(
        windows: List<MarketCaptureWindow>,
        records: List<MarketTransactionRecord> = emptyList(),
        firstCoverage: MarketWindowCoverage = MarketWindowCoverage.COMPLETE,
    ): MarketHistoryManifest {
        val selection = records.map { MarketListedSignature(it.signature, it.slot) }
        val windowRecords = windows.mapIndexed { index, window ->
            val coverage = if (index == 0) firstCoverage else MarketWindowCoverage.COMPLETE
            val listed = if (coverage == MarketWindowCoverage.COMPLETE) selection else emptyList()
            MarketWindowRecord(window.baseMint.value, MintAddress.WRAPPED_SOL.value, window.fromSlot.value, window.toSlot.value,
                window.untilSignature.value, coverage, pagesScanned = when (coverage) {
                    MarketWindowCoverage.COMPLETE, MarketWindowCoverage.PENDING -> 2
                    else -> SCAN_PAGES
                },
                scanned = selection.size + 1002, skippedFailed = 1000, skippedNewer = 2, selected = selection.size, selection = listed,
                scanCursor = listed.lastOrNull() ?: MarketListedSignature(window.untilSignature.value, window.toSlot.value))
        }
        val acquired = windowRecords.none { it.coverage == MarketWindowCoverage.PENDING } &&
            records.none { it.outcome == MarketTransactionOutcome.PENDING }
        return MarketHistoryManifest(
            schemaVersion = 2, genesisHash = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d", commitment = "confirmed",
            capturedAt = "2026-10-04T00:00:00Z", windowParameters = PARAMETERS, scanPageLimit = SCAN_PAGES, maxTransactions = 300,
            windows = windowRecords, records = records, acquisitionComplete = acquired,
            complete = acquired && windowRecords.all { it.coverage == MarketWindowCoverage.COMPLETE }, requestsUsed = 5, bytesUsed = 4096,
        )
    }

    /** A decode of [manifest] whose first window yields [observations] observations, one per slot from its first slot. */
    fun decoded(directory: Path, manifest: MarketHistoryManifest, observations: Int): MarketHistoryDecodeResult {
        val first = manifest.windows.first()
        val window = MarketCaptureWindow.of(MintAddress.of(first.baseMint), Slot.of(first.fromSlot), Slot.of(first.toSlot),
            TransactionSignature.of(first.untilSignature))
        val listed = (0 until observations).map { index ->
            MarketObservation("pumpfun-bonding-curve-trade/v1:observation$index", window.pair, window.fromSlot.plus(index.toLong()),
                window.fromSlot.plus(index.toLong()), Instant.ofEpochSecond(1791049629), BigDecimal("0.00000019736807582073671197813450558296"),
                null, "pumpfun-bonding-curve-trade/v1", "observation$index", Commitment.CONFIRMED, ObservationQuality.LAST_TRADE, false, CURVE,
                DipSampleV4.trade(index))
        }
        return MarketHistoryDecodeResult(directory, manifest.complete, MarketObservationExtractorVersion.PUMPFUN_BONDING_CURVE_TRADE_V2,
            manifest.windowParameters, listOf(MarketWindowDecodeRecord(window, first.coverage, first.selected, 0, true, observations)),
            manifest.records.map { MarketTransactionDecodeRecord(TransactionSignature.of(it.signature), Slot.of(it.slot), it.outcome, observations, 0) },
            listed, MarketObservationExclusion.entries.associateWith { 0 })
    }
}
