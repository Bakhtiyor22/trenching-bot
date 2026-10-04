package com.trenching.bot.marketdata

/**
 * [acquisitionComplete] means no window or transaction still needs a provider request. [complete] additionally
 * requires every window to be [MarketWindowCoverage.COMPLETE]; a window at the page bound or over the cap stays a gap.
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
 * the window is pending or complete, because the other coverages fetch no details.
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
)

data class MarketListedSignature(val signature: String, val slot: Long)

enum class MarketWindowCoverage {
    PENDING,
    COMPLETE,
    COVERAGE_INCOMPLETE,
    OVER_CAP,
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
}
