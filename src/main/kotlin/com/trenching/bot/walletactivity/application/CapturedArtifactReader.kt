package com.trenching.bot.walletactivity.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.VerificationListing
import com.trenching.bot.walletactivity.WalletHistoryManifest
import com.trenching.bot.walletactivity.WalletHistoryOutcome
import com.trenching.bot.walletactivity.domain.CreationCandidateKind
import com.trenching.bot.walletactivity.domain.TokenAccountRecordKind
import com.trenching.bot.walletactivity.domain.VerificationEntryKind
import com.trenching.bot.walletactivity.infrastructure.capture.TokenTarget
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoder
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoding
import java.nio.file.Path

/**
 * One verified pass over a capture directory: every wallet-signed token-account artifact is decoded and, for a token-account
 * capture, every verification artifact is classified. Each artifact is used only after the store's hash and identity checks.
 */
internal class CapturedArtifactReader(private val store: WalletHistoryArtifactStore, private val decoder: PumpFunBuyDecoder) {
    /** [expected] is the manifest a prior inspection verified; a directory that changed since is refused. */
    fun read(directory: Path, expected: WalletHistoryManifest, wallet: WalletAddress): CapturedArtifacts {
        val target = TokenTarget.of(expected)
        val assessment = target?.let { TargetTokenAssessment(wallet, it, decoder) }
        val decodings = mutableMapOf<String, PumpFunBuyDecoding>()
        val inspected = mutableMapOf<String, TokenAccountRecordKind>()
        val entryKinds = mutableMapOf<String, VerificationEntryKind>()
        val candidateKinds = mutableMapOf<String, CreationCandidateKind>()
        val tokenAccountCandidates = mutableMapOf<String, CreationCandidateKind>()
        store.loadIfPresent(directory,
            onArtifact = { record, transaction ->
                if (record.outcome == WalletHistoryOutcome.UNCLASSIFIED) {
                    val decoding = decoder.decode(transaction, wallet, target?.mint)
                    decodings[record.signature] = decoding
                    assessment?.let {
                        inspected[record.signature] = it.inspect(transaction, decoding)
                        tokenAccountCandidates[record.signature] = it.candidateKind(transaction)
                    }
                }
            },
            onVerificationArtifact = { record, transaction ->
                val classifier = checkNotNull(assessment) { "Verification artifact outside a token-account capture signature=${record.signature}" }
                when (record.listing) {
                    VerificationListing.MINT -> candidateKinds[record.signature] = classifier.candidateKind(transaction)
                    VerificationListing.PUMP_ACCUMULATOR, VerificationListing.PUMPSWAP_ACCUMULATOR ->
                        entryKinds[record.signature] = classifier.entryKind(transaction)
                }
            },
        )?.takeIf { it == expected } ?: throw CaptureValidationException("CAPTURE_CHANGED")
        return CapturedArtifacts(expected, decodings, inspected, VerificationKinds(entryKinds, candidateKinds, tokenAccountCandidates), assessment)
    }
}
