package com.trenching.bot.walletactivity.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.TargetTokenEvidence
import com.trenching.bot.walletactivity.TokenAccountFirstBuy
import com.trenching.bot.walletactivity.WalletHistoryManifest
import com.trenching.bot.walletactivity.WalletHistoryOutcome
import com.trenching.bot.walletactivity.WalletHistoryRecord
import com.trenching.bot.walletactivity.domain.CreationCandidateKind
import com.trenching.bot.walletactivity.domain.TargetBalanceChange
import com.trenching.bot.walletactivity.domain.TokenAccountFirstBuyRule
import com.trenching.bot.walletactivity.domain.TokenAccountRecord
import com.trenching.bot.walletactivity.domain.TokenAccountRecordKind
import com.trenching.bot.walletactivity.domain.VerificationEntryKind
import com.trenching.bot.walletactivity.infrastructure.capture.TokenTarget
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryListing
import com.trenching.bot.walletactivity.infrastructure.capture.WalletTransactionEvidence
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoder
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoding
import com.trenching.bot.walletactivity.infrastructure.decoder.TargetMintActivity
import com.trenching.bot.walletactivity.infrastructure.decoder.TargetTokenBalances
import java.time.Instant
import tools.jackson.databind.JsonNode

/** Reduces a token-account capture's artifacts to what decides its first buys, and assembles [TargetTokenEvidence]. */
internal class TargetTokenAssessment(
    private val wallet: WalletAddress,
    private val target: TokenTarget,
    private val decoder: PumpFunBuyDecoder,
) {
    private val tokenAccount = WalletHistoryListing.tokenAccount(wallet, target)
    private val transactions = WalletTransactionEvidence()

    /** What a wallet-signed token-account artifact shows about the target mint, from its decoding and balances. */
    fun inspect(transaction: JsonNode, decoding: PumpFunBuyDecoding): TokenAccountRecordKind = when (decoding) {
        is PumpFunBuyDecoding.Decoded -> TokenAccountRecordKind.TargetBuy(decoding.transaction.instructionIndex,
            TargetTokenBalances.credits(transaction, tokenAccount, target.mint))
        is PumpFunBuyDecoding.Rejected -> TokenAccountRecordKind.Undecoded(TargetTokenBalances.walletChange(transaction, wallet, target.mint))
    }

    /** Step 1 over resolved records; [inspected] holds [inspect]'s result for every wallet-signed artifact, by signature. */
    fun tokenAccountFirstBuy(manifest: WalletHistoryManifest, inspected: Map<String, TokenAccountRecordKind>): TokenAccountFirstBuy =
        TokenAccountFirstBuyRule.evaluate(manifest.discoveryStop, manifest.records.map { record ->
            TokenAccountRecord(TransactionSignature.of(record.signature), Slot.of(record.slot), kind(record, inspected))
        })

    /** What an accumulator entry between the creation and B shows about an earlier acquisition of the target mint. */
    fun entryKind(transaction: JsonNode): VerificationEntryKind {
        if (!TargetMintActivity.mentions(transaction, target.mint) || !transactions.signedBy(transaction, wallet.value)) {
            return VerificationEntryKind.IRRELEVANT
        }
        return when (decoder.decode(transaction, wallet, target.mint)) {
            is PumpFunBuyDecoding.Decoded -> VerificationEntryKind.WALLET_BUY
            is PumpFunBuyDecoding.Rejected -> when (TargetTokenBalances.walletChange(transaction, wallet, target.mint)) {
                TargetBalanceChange.NOT_INCREASED -> VerificationEntryKind.IRRELEVANT
                TargetBalanceChange.INCREASED, TargetBalanceChange.UNMEASURABLE -> VerificationEntryKind.UNDECODED_ACQUISITION
            }
        }
    }

    /** Whether one of the mint's oldest transactions creates it, trades it, or neither. */
    fun candidateKind(transaction: JsonNode): CreationCandidateKind = when {
        TargetMintActivity.creates(transaction, target.mint) ->
            CreationCandidateKind.Creation(TargetMintActivity.blockTime(transaction)?.let(Instant::ofEpochSecond))
        TargetMintActivity.trades(transaction, target.mint) -> CreationCandidateKind.Trade
        else -> CreationCandidateKind.Other
    }

    fun evidence(tokenAccountFirstBuy: TokenAccountFirstBuy, actualFirstBuy: ActualFirstBuy): TargetTokenEvidence = TargetTokenEvidence(
        mint = target.mint,
        tokenProgram = target.tokenProgram,
        tokenAccount = tokenAccount,
        pumpAccumulator = WalletHistoryListing.pumpAccumulator(wallet),
        pumpSwapAccumulator = WalletHistoryListing.pumpSwapAccumulator(wallet),
        tokenAccountFirstBuy = tokenAccountFirstBuy,
        actualFirstBuy = actualFirstBuy,
    )

    private fun kind(record: WalletHistoryRecord, inspected: Map<String, TokenAccountRecordKind>): TokenAccountRecordKind =
        when (record.outcome) {
            WalletHistoryOutcome.UNCLASSIFIED ->
                checkNotNull(inspected[record.signature]) { "Wallet-signed record was not inspected signature=${record.signature}" }
            WalletHistoryOutcome.NON_SIGNER, WalletHistoryOutcome.WALLET_ABSENT, WalletHistoryOutcome.FAILED -> TokenAccountRecordKind.Irrelevant
            WalletHistoryOutcome.UNAVAILABLE, WalletHistoryOutcome.UNSUPPORTED_VERSION, WalletHistoryOutcome.WITHHELD_UNSAFE_TEXT ->
                TokenAccountRecordKind.Unknown
            WalletHistoryOutcome.PENDING, WalletHistoryOutcome.PROVIDER_UNAVAILABLE, WalletHistoryOutcome.INVALID_TRANSACTION ->
                throw CaptureValidationException("CAPTURE_INCOMPLETE")
        }
}
