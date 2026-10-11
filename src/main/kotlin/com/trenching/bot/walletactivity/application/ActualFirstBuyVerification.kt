package com.trenching.bot.walletactivity.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.walletactivity.AccumulatorScan
import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.TokenAccountFirstBuy
import com.trenching.bot.walletactivity.VerificationListing
import com.trenching.bot.walletactivity.VerificationOutcome
import com.trenching.bot.walletactivity.VerificationRecord
import com.trenching.bot.walletactivity.VerificationResult
import com.trenching.bot.walletactivity.VerificationResultKind
import com.trenching.bot.walletactivity.VerifiedFirstBuy
import com.trenching.bot.walletactivity.WalletHistoryManifest
import com.trenching.bot.walletactivity.WalletTokenVerification
import com.trenching.bot.walletactivity.domain.ActualFirstBuyEvidence
import com.trenching.bot.walletactivity.domain.ActualFirstBuyRule
import com.trenching.bot.walletactivity.domain.CreationCandidate
import com.trenching.bot.walletactivity.domain.CreationCandidateKind
import com.trenching.bot.walletactivity.domain.CreationOutcome
import com.trenching.bot.walletactivity.domain.CreationSearchEvidence
import com.trenching.bot.walletactivity.domain.ListingScanEvidence
import com.trenching.bot.walletactivity.domain.VerificationEntry
import com.trenching.bot.walletactivity.domain.VerificationEntryKind

/**
 * Turns a manifest's verification section and its classified artifacts into [ActualFirstBuyEvidence], and keeps the section's
 * derived fields (creation identity, scan completeness, verdict) in step with [ActualFirstBuyRule]. Capture uses it to write
 * them; inspection uses it to refuse a section whose derived fields do not follow from its evidence.
 */
internal object ActualFirstBuyVerification {
    /** The token-account first buy step 2 verifies, or null when step 1 found none. */
    fun target(tokenAccountFirstBuy: TokenAccountFirstBuy): VerifiedFirstBuy? = when (tokenAccountFirstBuy) {
        is TokenAccountFirstBuy.Found -> VerifiedFirstBuy(tokenAccountFirstBuy.signature.value, tokenAccountFirstBuy.slot.value, tokenAccountFirstBuy.instructionIndex)
        TokenAccountFirstBuy.NoBuy, is TokenAccountFirstBuy.NotEstablished -> null
    }

    /** The section with its creation identity and scan completeness derived from its evidence; the verdict is left as is. */
    fun settled(verification: WalletTokenVerification, firstBuy: VerifiedFirstBuy, kinds: VerificationKinds): WalletTokenVerification {
        val evidence = evidence(verification, firstBuy, kinds)
        val creation = ActualFirstBuyRule.creation(evidence) as? CreationOutcome.Identified
        return verification.copy(
            creation = verification.creation.copy(signature = creation?.signature?.value, slot = creation?.slot?.value,
                blockTime = creation?.blockTime?.epochSecond),
            pumpAccumulator = verification.pumpAccumulator.copy(complete = covers(evidence.pumpAccumulator, creation)),
            pumpSwapAccumulator = verification.pumpSwapAccumulator.copy(complete = covers(evidence.pumpSwapAccumulator, creation)),
        )
    }

    fun creation(verification: WalletTokenVerification, firstBuy: VerifiedFirstBuy, kinds: VerificationKinds): CreationOutcome =
        ActualFirstBuyRule.creation(evidence(verification, firstBuy, kinds))

    fun verdict(verification: WalletTokenVerification, firstBuy: VerifiedFirstBuy, kinds: VerificationKinds): ActualFirstBuy =
        ActualFirstBuyRule.evaluate(evidence(verification, firstBuy, kinds))

    /** The stored form of a reached verdict; null for [ActualFirstBuy.NotVerified]. */
    fun stored(result: ActualFirstBuy): VerificationResult? = when (result) {
        is ActualFirstBuy.Proven -> VerificationResult(VerificationResultKind.PROVEN, result.signature.value, null, null, emptyList())
        is ActualFirstBuy.Unproven ->
            VerificationResult(VerificationResultKind.UNPROVEN, null, result.reason, result.bound?.limit, result.blocking.map { it.value })
        is ActualFirstBuy.OutsideTokenAccount -> VerificationResult(VerificationResultKind.OUTSIDE_TOKEN_ACCOUNT, result.signature.value, null, null, emptyList())
        ActualFirstBuy.NotVerified -> null
    }

    /**
     * Recomputes the actual first buy of a token-account capture whose step 1 resolved, and refuses a section whose target is
     * not [tokenAccountFirstBuy] (`VERIFICATION_TARGET`) or whose derived fields or verdict differ (`VERIFICATION_RESULT`).
     */
    fun check(manifest: WalletHistoryManifest, tokenAccountFirstBuy: TokenAccountFirstBuy, kinds: VerificationKinds): ActualFirstBuy {
        val verification = manifest.verification ?: return ActualFirstBuy.NotVerified
        if (verification.firstBuy != target(tokenAccountFirstBuy)) throw CaptureValidationException("VERIFICATION_TARGET")
        val firstBuy = verification.firstBuy ?: return ActualFirstBuy.NotVerified
        if (settled(verification, firstBuy, kinds) != verification) throw CaptureValidationException("VERIFICATION_RESULT")
        if (verification.creation.signature == firstBuy.signature && listedMint(verification)) throw CaptureValidationException("VERIFICATION_LISTING")
        val verdict = verdict(verification, firstBuy, kinds)
        if (stored(verdict) != verification.result) throw CaptureValidationException("VERIFICATION_RESULT")
        return verdict
    }

    private fun evidence(verification: WalletTokenVerification, firstBuy: VerifiedFirstBuy, kinds: VerificationKinds): ActualFirstBuyEvidence {
        val creation = verification.creation
        val (candidates, entries) = verification.records.partition { it.listing == VerificationListing.MINT }
        return ActualFirstBuyEvidence(
            firstBuy = TokenAccountFirstBuy.Found(TransactionSignature.of(firstBuy.signature), Slot.of(firstBuy.slot), firstBuy.instructionIndex),
            firstBuyKind = checkNotNull(kinds.tokenAccountCandidates[firstBuy.signature]) { "First buy was not classified signature=${firstBuy.signature}" },
            creation = CreationSearchEvidence(
                listing = listing(creation.pages, creation.entries, creation.oldestSlot),
                candidates = creation.candidates.size,
                fetched = candidates.map { CreationCandidate(TransactionSignature.of(it.signature), Slot.of(it.slot), candidateKind(it, kinds)) },
            ),
            pumpAccumulator = scan(verification.pumpAccumulator),
            pumpSwapAccumulator = scan(verification.pumpSwapAccumulator),
            pumpListedFirstBuy = verification.pumpAccumulator.listedFirstBuy,
            entries = entries.map { VerificationEntry(TransactionSignature.of(it.signature), Slot.of(it.slot), it.listing, entryKind(it, kinds)) },
            fetches = verification.fetches,
        )
    }

    /** A creating B needs no mint listing, so a section that holds one did not come from capture. */
    private fun listedMint(verification: WalletTokenVerification): Boolean =
        verification.creation.pages > 0 || verification.creation.candidates.isNotEmpty() || verification.records.any { it.listing == VerificationListing.MINT }

    private fun covers(scan: ListingScanEvidence, creation: CreationOutcome.Identified?): Boolean =
        creation != null && ActualFirstBuyRule.covers(scan, creation.slot)

    private fun scan(scan: AccumulatorScan): ListingScanEvidence = listing(scan.pages, scan.entries, scan.oldestSlot)

    private fun listing(pages: Int, entries: Int, oldestSlot: Long?) =
        ListingScanEvidence(pages, entries, oldestSlot, endedWithinPage = pages > 0 && entries < pages * ChainCaptureFormat.SIGNATURE_PAGE_SIZE)

    private fun candidateKind(record: VerificationRecord, kinds: VerificationKinds): CreationCandidateKind = when (record.outcome) {
        VerificationOutcome.CAPTURED -> checkNotNull(kinds.candidates[record.signature]) { "Captured candidate was not classified signature=${record.signature}" }
        VerificationOutcome.UNAVAILABLE, VerificationOutcome.UNSUPPORTED_VERSION, VerificationOutcome.WITHHELD_UNSAFE_TEXT -> CreationCandidateKind.Unknown
        VerificationOutcome.PENDING, VerificationOutcome.INVALID_TRANSACTION, VerificationOutcome.PROVIDER_UNAVAILABLE -> CreationCandidateKind.Unresolved
    }

    private fun entryKind(record: VerificationRecord, kinds: VerificationKinds): VerificationEntryKind = when (record.outcome) {
        VerificationOutcome.CAPTURED -> checkNotNull(kinds.entries[record.signature]) { "Captured entry was not classified signature=${record.signature}" }
        VerificationOutcome.UNAVAILABLE, VerificationOutcome.UNSUPPORTED_VERSION, VerificationOutcome.WITHHELD_UNSAFE_TEXT -> VerificationEntryKind.UNKNOWN
        VerificationOutcome.PENDING, VerificationOutcome.INVALID_TRANSACTION, VerificationOutcome.PROVIDER_UNAVAILABLE -> VerificationEntryKind.UNRESOLVED
    }
}
