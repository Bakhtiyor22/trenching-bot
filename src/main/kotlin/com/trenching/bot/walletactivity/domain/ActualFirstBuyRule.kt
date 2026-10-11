package com.trenching.bot.walletactivity.domain

import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.protocol.pumpfun.PumpFunCoverage
import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.FirstBuyGap
import com.trenching.bot.walletactivity.VerificationBoundHit
import com.trenching.bot.walletactivity.VerificationLimit
import com.trenching.bot.walletactivity.VerificationListing
import com.trenching.bot.walletactivity.WalletHistoryCaptureLimits

/**
 * Step 2 of the wallet-token capture plan: whether the token-account first buy B is the wallet's actual first Pump.fun curve
 * buy of the mint. B is proven only when its mint's creation was identified after the accumulator coverage date, both of the
 * wallet's accumulator listings were scanned below the creation, the Pump accumulator listed B itself, and no entry from the
 * creation's slot to B's slot could be an earlier acquisition. A buy that creates its own mint is its own creation. Within-slot
 * order is not relied on, so every other entry in B's slot counts as earlier. The earliest decoded wallet buy decides as soon
 * as no unresolved entry precedes it or shares its slot, ahead of any gap or cap. A cap reached before its bound is a
 * [FirstBuyGap.VERIFICATION_BOUND] verdict; evidence that needs more acquisition is [ActualFirstBuy.NotVerified].
 */
internal object ActualFirstBuyRule {
    private val CANONICAL = compareBy<VerificationEntry>({ it.slot }, { it.signature.value })

    fun evaluate(evidence: ActualFirstBuyEvidence): ActualFirstBuy {
        val creation = when (val outcome = creation(evidence)) {
            is CreationOutcome.Identified -> outcome
            is CreationOutcome.Decided -> return outcome.result
            CreationOutcome.Pending -> return ActualFirstBuy.NotVerified
        }
        val time = creation.blockTime
        if (time == null || time < PumpFunCoverage.ACCUMULATOR_REQUIRED_FROM) {
            return unproven(FirstBuyGap.PRE_ACCUMULATOR_MINT, listOf(creation.signature))
        }
        uncovered(evidence, evidence.pumpAccumulator, creation.slot, VerificationLimit.PUMP_ACCUMULATOR_PAGES)?.let { return it }
        if (!evidence.pumpListedFirstBuy) return unproven(FirstBuyGap.FIRST_BUY_NOT_LISTED, listOf(evidence.firstBuy.signature))
        uncovered(evidence, evidence.pumpSwapAccumulator, creation.slot, VerificationLimit.PUMPSWAP_ACCUMULATOR_PAGES)?.let { return it }
        return entries(evidence, creation)
    }

    /** B itself when it creates the mint, else the creation the oldest-first candidate fetches identify, or the verdict they settle. */
    fun creation(evidence: ActualFirstBuyEvidence): CreationOutcome {
        (evidence.firstBuyKind as? CreationCandidateKind.Creation)?.let { own ->
            return CreationOutcome.Identified(evidence.firstBuy.signature, evidence.firstBuy.slot, own.blockTime)
        }
        val search = evidence.creation
        if (!search.listing.endedWithinPage) {
            return if (pagesSpent(search.listing)) CreationOutcome.Decided(bound(evidence, VerificationLimit.MINT_PAGES)) else CreationOutcome.Pending
        }
        for (candidate in search.fetched) {
            when (val kind = candidate.kind) {
                is CreationCandidateKind.Creation -> return CreationOutcome.Identified(candidate.signature, candidate.slot, kind.blockTime)
                CreationCandidateKind.Trade -> return CreationOutcome.Decided(unproven(FirstBuyGap.CREATION_NOT_IDENTIFIED, listOf(candidate.signature)))
                CreationCandidateKind.Unresolved -> return fetchNeeded(evidence)
                CreationCandidateKind.Other, CreationCandidateKind.Unknown -> Unit
            }
        }
        val allowance = minOf(search.candidates, WalletHistoryCaptureLimits.CREATION_FETCHES)
        return if (search.fetched.size >= allowance) CreationOutcome.Decided(unproven(FirstBuyGap.CREATION_NOT_IDENTIFIED, emptyList()))
        else fetchNeeded(evidence)
    }

    /** Whether an accumulator scan reached its first entry below [creationSlot] or the end of its listing. */
    fun covers(scan: ListingScanEvidence, creationSlot: Slot): Boolean =
        scan.pages > 0 && (scan.endedWithinPage || (scan.oldestSlot ?: Long.MAX_VALUE) < creationSlot.value)

    private fun entries(evidence: ActualFirstBuyEvidence, creation: CreationOutcome.Identified): ActualFirstBuy {
        val buy = evidence.firstBuy
        val earlier = evidence.entries
            .filter { it.slot >= creation.slot && it.slot <= buy.slot && it.signature != buy.signature }
            .sortedWith(CANONICAL)
        val unresolved = earlier.filter { it.kind == VerificationEntryKind.UNRESOLVED }
        earlier.firstOrNull { it.kind == VerificationEntryKind.WALLET_BUY }
            ?.takeIf { buy -> unresolved.none { it.slot <= buy.slot } }
            ?.let { return ActualFirstBuy.OutsideTokenAccount(it.signature, it.slot) }
        if (unresolved.isNotEmpty()) {
            return if (fetchesSpent(evidence)) bound(evidence, VerificationLimit.FETCHES) else ActualFirstBuy.NotVerified
        }
        listOf(
            VerificationEntryKind.UNDECODED_ACQUISITION to FirstBuyGap.EARLIER_UNDECODED_ACQUISITION,
            VerificationEntryKind.UNKNOWN to FirstBuyGap.EARLIER_UNKNOWN_RECORD,
        ).forEach { (kind, gap) ->
            val blocking = earlier.filter { it.kind == kind }.map { it.signature }.distinct()
            if (blocking.isNotEmpty()) return unproven(gap, blocking)
        }
        return ActualFirstBuy.Proven(
            signature = buy.signature,
            slot = buy.slot,
            instructionIndex = buy.instructionIndex,
            creationSignature = creation.signature,
            creationSlot = creation.slot,
            creationTime = checkNotNull(creation.blockTime) { "Covered creation without a block time signature=${creation.signature.value}" },
            scannedTo = listings(evidence).mapNotNull { (listing, scan) -> scan.oldestSlot?.let { listing to Slot.of(it) } }.toMap(),
        )
    }

    /** Null once [scan] covers the creation; otherwise its cap verdict, or [ActualFirstBuy.NotVerified] while it can go on. */
    private fun uncovered(evidence: ActualFirstBuyEvidence, scan: ListingScanEvidence, creationSlot: Slot, limit: VerificationLimit): ActualFirstBuy? = when {
        covers(scan, creationSlot) -> null
        pagesSpent(scan) -> bound(evidence, limit)
        else -> ActualFirstBuy.NotVerified
    }

    private fun fetchNeeded(evidence: ActualFirstBuyEvidence): CreationOutcome =
        if (fetchesSpent(evidence)) CreationOutcome.Decided(bound(evidence, VerificationLimit.FETCHES)) else CreationOutcome.Pending

    private fun pagesSpent(scan: ListingScanEvidence): Boolean = scan.pages >= WalletHistoryCaptureLimits.VERIFICATION_LISTING_PAGES

    private fun fetchesSpent(evidence: ActualFirstBuyEvidence): Boolean = evidence.fetches >= WalletHistoryCaptureLimits.VERIFICATION_FETCHES

    private fun bound(evidence: ActualFirstBuyEvidence, limit: VerificationLimit): ActualFirstBuy {
        val listings = listings(evidence)
        return ActualFirstBuy.Unproven(FirstBuyGap.VERIFICATION_BOUND, emptyList(), VerificationBoundHit(
            limit = limit,
            pages = listings.associate { (listing, scan) -> listing to scan.pages },
            entries = listings.associate { (listing, scan) -> listing to scan.entries },
            fetches = evidence.fetches,
            oldestSlot = listings.associate { (listing, scan) -> listing to scan.oldestSlot },
        ))
    }

    private fun listings(evidence: ActualFirstBuyEvidence): List<Pair<VerificationListing, ListingScanEvidence>> = listOf(
        VerificationListing.MINT to evidence.creation.listing,
        VerificationListing.PUMP_ACCUMULATOR to evidence.pumpAccumulator,
        VerificationListing.PUMPSWAP_ACCUMULATOR to evidence.pumpSwapAccumulator,
    )

    private fun unproven(reason: FirstBuyGap, blocking: List<TransactionSignature>): ActualFirstBuy = ActualFirstBuy.Unproven(reason, blocking, null)
}
