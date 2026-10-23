package com.trenching.bot.marketdata

import java.time.Instant

/**
 * [acquisitionComplete] means no window or transaction still needs a provider request. [complete] additionally
 * requires every window to be [MarketWindowCoverage.COMPLETE]; a window at the page bound or over the cap stays a gap.
 * A window whose horizon has not elapsed still needs requests, so it keeps both flags false.
 */
data class MarketHistoryManifest(
    val schemaVersion: Int,
    val genesisHash: String,
    val commitment: String,
    val capturedAt: String,
    val windowParameters: MarketCaptureWindowParameters,
    val scanPageLimit: Int,
    val maxTransactions: Int,
    val windows: List<MarketWindowRecord>,
    val records: List<MarketTransactionRecord>,
    val acquisitionComplete: Boolean,
    val complete: Boolean,
    val requestsUsed: Int,
    val bytesUsed: Long,
)

/**
 * One window's discovery audit. [selected] counts in-window successful signatures; [selection] keeps them only while
 * the window is pending or complete, because the other coverages fetch no details. [cutoff] is set while the window's
 * outcome is decided at a slot before its end; the window is then observed only through that slot. [listedAtTip] is the
 * confirmed tip read before the listing started, at or past [toSlot]; windows listed before it was recorded have none.
 * [throughBlockTime] is the block time of the nearest produced block at or below the slot the window is observed through
 * (its cutoff, or [toSlot]), read once the window is settled; only a version 5 capture records it, and a failed read leaves
 * it null until a resume reads it again.
 */
data class MarketWindowRecord(
    val baseMint: String,
    val quoteMint: String,
    val fromSlot: Long,
    val toSlot: Long,
    val untilSignature: String,
    val coverage: MarketWindowCoverage,
    val pagesScanned: Int,
    val scanned: Int,
    val skippedFailed: Int,
    val skippedNewer: Int,
    val selected: Int,
    val selection: List<MarketListedSignature>,
    val scanCursor: MarketListedSignature?,
    val cutoff: MarketWindowCutoff? = null,
    val listedAtTip: Long? = null,
    val throughBlockTime: Instant? = null,
)

/** Where a decided window stops being observed, which outcome decided it, and the evaluator's identity. */
data class MarketWindowCutoff(
    val throughSlot: Long,
    val outcome: String,
    val engineVersion: String,
    val configurationHash: String,
)

data class MarketListedSignature(val signature: String, val slot: Long)

enum class MarketWindowCoverage {
    PENDING,
    COMPLETE,
    COVERAGE_INCOMPLETE,
    OVER_CAP,

    /**
     * The confirmed tip had not reached the window's end, so its listing could not be final yet: nothing of it is listed or
     * fetched, and a later resume lists it once the tip has passed its end. Like [PENDING], it still needs provider requests.
     */
    HORIZON_NOT_ELAPSED,
}

data class MarketTransactionRecord(
    val signature: String,
    val slot: Long,
    val outcome: MarketTransactionOutcome,
    val artifact: String? = null,
    val sha256: String? = null,
    val bytes: Long = 0,
)

enum class MarketTransactionOutcome {
    PENDING,
    CAPTURED,
    UNAVAILABLE,
    UNSUPPORTED_VERSION,
    INVALID_TRANSACTION,
    PROVIDER_UNAVAILABLE,
    WITHHELD_UNSAFE_TEXT,

    /** Every window that selects it was decided before its slot; it is resolved, never fetched or referenced. */
    NOT_NEEDED,
}
