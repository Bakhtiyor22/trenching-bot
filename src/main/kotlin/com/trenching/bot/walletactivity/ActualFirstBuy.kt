package com.trenching.bot.walletactivity

import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import java.time.Instant

/** The wallet's first Pump.fun bonding-curve buy of the target mint by any curve route, whichever token account it credited. */
sealed interface ActualFirstBuy {
    /**
     * Proven for documented routes, for a mint created after the accumulator coverage date: the creation was identified and
     * both accumulator scans reached below it. [scannedTo] is the oldest slot each listing reached.
     */
    data class Proven(
        val signature: TransactionSignature,
        val slot: Slot,
        val instructionIndex: Int,
        val creationSignature: TransactionSignature,
        val creationSlot: Slot,
        val creationTime: Instant,
        val scannedTo: Map<VerificationListing, Slot>,
    ) : ActualFirstBuy

    /** [bound] is set only for [FirstBuyGap.VERIFICATION_BOUND]. */
    data class Unproven(
        val reason: FirstBuyGap,
        val blocking: List<TransactionSignature>,
        val bound: VerificationBoundHit?,
    ) : ActualFirstBuy

    /** An earlier decoded buy of the mint credited another account; it is reported and never adopted. */
    data class OutsideTokenAccount(val signature: TransactionSignature, val slot: Slot) : ActualFirstBuy

    /** The token-account first buy was not found, or verification has not reached a result. */
    data object NotVerified : ActualFirstBuy
}

enum class FirstBuyGap {
    CREATION_NOT_IDENTIFIED,

    /** The creation transaction predates the accumulator coverage date, or has no block time. */
    PRE_ACCUMULATOR_MINT,
    EARLIER_UNDECODED_ACQUISITION,
    EARLIER_UNKNOWN_RECORD,
    VERIFICATION_BOUND,

    /** The Pump accumulator scan reached below the creation without listing B, so its index cannot be trusted. */
    FIRST_BUY_NOT_LISTED,
}

/** The signature listings verification scans. */
enum class VerificationListing { MINT, PUMP_ACCUMULATOR, PUMPSWAP_ACCUMULATOR }

enum class VerificationLimit { MINT_PAGES, PUMP_ACCUMULATOR_PAGES, PUMPSWAP_ACCUMULATOR_PAGES, FETCHES }

/** Which verification cap was reached, with how far each listing was scanned when it was. */
data class VerificationBoundHit(
    val limit: VerificationLimit,
    val pages: Map<VerificationListing, Int>,
    val entries: Map<VerificationListing, Int>,
    val fetches: Int,
    val oldestSlot: Map<VerificationListing, Long?>,
)
