package com.trenching.bot.walletactivity

/**
 * Step 2 of a [WalletHistoryScope.WALLET_TOKEN_ACCOUNT] capture: the evidence that decides the wallet's [ActualFirstBuy].
 * It is written once step 1 has resolved every record. [firstBuy] is the token-account first buy B under verification, or
 * null when step 1 found none, in which case every other section stays empty. [result] is the verdict the evidence gives,
 * null until it gives one; inspection recomputes it from the evidence and refuses a manifest whose stored verdict differs.
 * [fetches] counts every transaction requested for step 2; reused artifacts are not counted.
 */
data class WalletTokenVerification(
    val firstBuy: VerifiedFirstBuy?,
    val creation: CreationSearch,
    val pumpAccumulator: AccumulatorScan,
    val pumpSwapAccumulator: AccumulatorScan,
    val records: List<VerificationRecord>,
    val fetches: Int,
    val result: VerificationResult?,
)

data class VerifiedFirstBuy(val signature: String, val slot: Long, val instructionIndex: Int)

/**
 * The target mint's signature listing before B, read newest first to its end, and the creation found among its oldest
 * successful entries. [candidates] keeps those entries, at most ten and newest first as listed; they are fetched oldest
 * first. [signature], [slot] and [blockTime] (epoch seconds) identify the creation transaction once one is found.
 */
data class CreationSearch(
    val cursor: WalletHistoryScanCursor?,
    val pages: Int,
    val entries: Int,
    val oldestSlot: Long?,
    val historyEnd: Boolean,
    val candidates: List<WalletHistorySelection>,
    val signature: String?,
    val slot: Long?,
    val blockTime: Long?,
)

/**
 * A newest-first scan of one of the wallet's volume accumulators, from the head of its listing down to its first entry below
 * the creation slot. [listedFirstBuy] is whether the scan listed B; [complete] means it reached that entry or the end of the
 * listing.
 */
data class AccumulatorScan(
    val address: String,
    val cursor: WalletHistoryScanCursor?,
    val pages: Int,
    val entries: Int,
    val skippedFailed: Int,
    val oldestSlot: Long?,
    val listedFirstBuy: Boolean,
    val complete: Boolean,
)

/**
 * A transaction step 2 inspects: a creation candidate from the [VerificationListing.MINT] listing, or an accumulator entry
 * between the creation and B. Its [artifact] is its own under `verification/`, or the token-account artifact it reuses.
 */
data class VerificationRecord(
    val signature: String,
    val slot: Long,
    val listing: VerificationListing,
    val outcome: VerificationOutcome,
    val artifact: String? = null,
    val sha256: String? = null,
    val bytes: Long = 0,
)

enum class VerificationOutcome {
    PENDING,

    /** Stored with an artifact: a valid transaction that contains its listing's address and no unsafe text. */
    CAPTURED,
    UNAVAILABLE,
    UNSUPPORTED_VERSION,
    WITHHELD_UNSAFE_TEXT,

    /** Malformed, of another identity, or without its listing's address; retried on the next run. */
    INVALID_TRANSACTION,
    PROVIDER_UNAVAILABLE,
}

/**
 * The stored form of a reached [ActualFirstBuy]. [signature] is B for [VerificationResultKind.PROVEN] and the earlier buy
 * for [VerificationResultKind.OUTSIDE_TOKEN_ACCOUNT]; [reason], [limit] and [blocking] describe an unproven one.
 */
data class VerificationResult(
    val kind: VerificationResultKind,
    val signature: String?,
    val reason: FirstBuyGap?,
    val limit: VerificationLimit?,
    val blocking: List<String>,
)

enum class VerificationResultKind { PROVEN, UNPROVEN, OUTSIDE_TOKEN_ACCOUNT }
