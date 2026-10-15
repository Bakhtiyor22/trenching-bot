package com.trenching.bot.marketdata

import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.domain.MarketObservation
import java.nio.file.Path

/**
 * A verified capture whose acquisition finished, turned into observations offline. [observations] are in canonical
 * order (slot, then id); [windows] and [transactions] keep the manifest's order; [exclusions] lists every reason.
 */
data class MarketHistoryDecodeResult(
    val directory: Path,
    val complete: Boolean,
    val extractor: MarketObservationExtractorVersion,
    val windowParameters: MarketCaptureWindowParameters,
    val windows: List<MarketWindowDecodeRecord>,
    val transactions: List<MarketTransactionDecodeRecord>,
    val observations: List<MarketObservation>,
    val exclusions: Map<MarketObservationExclusion, Int>,
)

/**
 * [yieldsObservations] holds only for a complete window whose every in-window transaction was captured; [detailGaps]
 * counts the in-window transactions that were not, so a gap is never read as an absence of trades.
 */
data class MarketWindowDecodeRecord(
    val window: MarketCaptureWindow,
    val coverage: MarketWindowCoverage,
    val selected: Int,
    val detailGaps: Int,
    val yieldsObservations: Boolean,
    val observations: Int,
)

data class MarketTransactionDecodeRecord(
    val signature: TransactionSignature,
    val slot: Slot,
    val outcome: MarketTransactionOutcome,
    val observations: Int,
    val exclusions: Int,
)

enum class MarketObservationExtractorVersion {
    PUMPFUN_BONDING_CURVE_TRADE_V1,

    /** V1 plus each observation's decoded trade actor, side and amounts. */
    PUMPFUN_BONDING_CURVE_TRADE_V2,
}

enum class MarketObservationExclusion {
    ZERO_AMOUNT,
    MALFORMED_EVENT,
    QUOTE_MINT,
    DECIMALS_UNPROVEN,
    BLOCK_TIME_MISSING,
    OUT_OF_WINDOW,

    /** The observation would not fit the replay format or its persisted columns; it is excluded, never truncated. */
    EXCEEDS_REPLAY_BOUNDS,
}
