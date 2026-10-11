package com.trenching.bot.walletactivity.domain

import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.TokenAccountFirstBuy
import com.trenching.bot.walletactivity.VerificationListing
import java.time.Instant

/**
 * What step 2 has gathered about the token-account first buy [firstBuy], reduced to what decides the actual first buy.
 * [firstBuyKind] is what B's own token-account artifact shows as a creation candidate: a creating buy is its own boundary.
 * [pumpListedFirstBuy] is whether the Pump accumulator scan listed B, which every decoded route passes.
 */
internal data class ActualFirstBuyEvidence(
    val firstBuy: TokenAccountFirstBuy.Found,
    val firstBuyKind: CreationCandidateKind,
    val creation: CreationSearchEvidence,
    val pumpAccumulator: ListingScanEvidence,
    val pumpSwapAccumulator: ListingScanEvidence,
    val pumpListedFirstBuy: Boolean,
    val entries: List<VerificationEntry>,
    val fetches: Int,
)

/**
 * [endedWithinPage] means the last page was not consumed to its end: the listing ran out, or an accumulator scan stopped at
 * its first entry below the creation.
 */
internal data class ListingScanEvidence(val pages: Int, val entries: Int, val oldestSlot: Long?, val endedWithinPage: Boolean)

/** [candidates] counts the oldest successful mint entries kept for fetching; [fetched] holds those fetched so far, oldest first. */
internal data class CreationSearchEvidence(val listing: ListingScanEvidence, val candidates: Int, val fetched: List<CreationCandidate>)

internal data class CreationCandidate(val signature: TransactionSignature, val slot: Slot, val kind: CreationCandidateKind)

internal sealed interface CreationCandidateKind {
    /** Runs a successful Pump.fun `create` or `create_v2` of the target mint. */
    data class Creation(val blockTime: Instant?) : CreationCandidateKind

    /** Holds a Pump.fun `TradeEvent` of the target mint without creating it. */
    data object Trade : CreationCandidateKind

    data object Other : CreationCandidateKind

    /** Fetched, but without an artifact to inspect. */
    data object Unknown : CreationCandidateKind

    /** Not fetched successfully yet. */
    data object Unresolved : CreationCandidateKind
}

/** A successful accumulator entry between the creation and B, from either accumulator listing. */
internal data class VerificationEntry(
    val signature: TransactionSignature,
    val slot: Slot,
    val listing: VerificationListing,
    val kind: VerificationEntryKind,
)

internal enum class VerificationEntryKind {
    /** Decodes as a buy of the target mint signed by the wallet. */
    WALLET_BUY,

    /** Mentions the target mint, is signed by the wallet, does not decode, and raised or may have raised its balance of the mint. */
    UNDECODED_ACQUISITION,

    /** Without an artifact to inspect. */
    UNKNOWN,

    /** Without the target mint, not signed by the wallet, or without any gain of the mint. */
    IRRELEVANT,

    /** Not fetched successfully yet. */
    UNRESOLVED,
}

internal sealed interface CreationOutcome {
    data class Identified(val signature: TransactionSignature, val slot: Slot, val blockTime: Instant?) : CreationOutcome

    /** The search settled the verdict without identifying a creation: not identified, or a cap. */
    data class Decided(val result: ActualFirstBuy) : CreationOutcome

    data object Pending : CreationOutcome
}
