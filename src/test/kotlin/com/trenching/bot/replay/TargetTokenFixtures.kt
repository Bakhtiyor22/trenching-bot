package com.trenching.bot.replay

import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.FirstBuyGap
import com.trenching.bot.walletactivity.TargetTokenEvidence
import com.trenching.bot.walletactivity.TokenAccountFirstBuy
import com.trenching.bot.walletactivity.TokenAccountGap
import com.trenching.bot.walletactivity.VerificationBoundHit
import com.trenching.bot.walletactivity.VerificationLimit
import com.trenching.bot.walletactivity.VerificationListing
import java.time.Instant

/** [TargetTokenEvidence] built directly, the way a wallet + token capture's decoding reports it. */
internal object TargetTokenFixtures {
    const val TOKEN_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    const val CREATION_SLOT_OFFSET = 100L
    val CREATION_TIME: Instant = Instant.parse("2026-10-01T00:00:00Z")
    val CREATION: TransactionSignature = signature(200)
    val EARLIER: TransactionSignature = signature(201)

    /** One refused first-buy state per row, with the reason market planning refuses it with. */
    fun refusals(signature: String, slot: Long): List<Pair<TargetTokenParts, String>> = listOf(
        TargetTokenParts(TokenAccountFirstBuy.NoBuy, ActualFirstBuy.NotVerified) to "NO_TARGET_BUY",
        TargetTokenParts(TokenAccountFirstBuy.NotEstablished(TokenAccountGap.HISTORY_NOT_EXHAUSTED, emptyList()),
            ActualFirstBuy.NotVerified) to "FIRST_BUY_UNPROVEN",
        TargetTokenParts(found(signature, slot), unproven()) to "FIRST_BUY_UNPROVEN",
        TargetTokenParts(found(signature, slot), ActualFirstBuy.OutsideTokenAccount(EARLIER, Slot.of(slot - 1))) to
            "FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT",
        TargetTokenParts(found(signature, slot), ActualFirstBuy.NotVerified) to "FIRST_BUY_UNPROVEN",
        TargetTokenParts(found(signature, slot), proven(EARLIER.value, slot)) to "FIRST_BUY_UNPROVEN",
    )

    fun evidence(mint: String, parts: TargetTokenParts) = TargetTokenEvidence(MintAddress.of(mint), TOKEN_PROGRAM,
        "TokenAccount111111111111111111111111111111", RecordedWalletFixtures.ACCUMULATOR, "PumpSwapAccumu1ator11111111111111111111111",
        parts.tokenAccountFirstBuy, parts.actualFirstBuy)

    fun provenEvidence(mint: String, signature: String, slot: Long) =
        evidence(mint, TargetTokenParts(found(signature, slot), proven(signature, slot)))

    fun found(signature: String, slot: Long) = TokenAccountFirstBuy.Found(TransactionSignature.of(signature), Slot.of(slot), 0)

    fun proven(signature: String, slot: Long) = ActualFirstBuy.Proven(TransactionSignature.of(signature), Slot.of(slot), 0,
        CREATION, Slot.of(slot - CREATION_SLOT_OFFSET), CREATION_TIME,
        mapOf(VerificationListing.PUMP_ACCUMULATOR to Slot.of(slot - CREATION_SLOT_OFFSET - 1),
            VerificationListing.PUMPSWAP_ACCUMULATOR to Slot.of(slot - CREATION_SLOT_OFFSET - 1)))

    fun unproven() = ActualFirstBuy.Unproven(FirstBuyGap.VERIFICATION_BOUND, emptyList(), VerificationBoundHit(
        VerificationLimit.FETCHES, mapOf(VerificationListing.MINT to 3), mapOf(VerificationListing.MINT to 2400), 500,
        mapOf(VerificationListing.MINT to 1000L)))

    private fun signature(index: Int): TransactionSignature =
        TransactionSignature.of(Base58.encode(ByteArray(64).also { it[62] = 1; it[63] = index.toByte() }))
}

internal data class TargetTokenParts(val tokenAccountFirstBuy: TokenAccountFirstBuy, val actualFirstBuy: ActualFirstBuy)
