package com.trenching.bot.replay.domain

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.MarketObservationExtractorVersion
import com.trenching.bot.walletactivity.PumpFunBuyInstruction
import com.trenching.bot.walletactivity.WalletHistoryDecoderVersion
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The execution model measured against a wallet's real Pump.fun buys, per the bonding-curve-execution plan's calibration
 * rule. [buys] are ordered by slot, then signature. Raw amounts are lamports and raw token units.
 */
data class CalibrationReport(
    val wallet: WalletAddress,
    val decoder: WalletHistoryDecoderVersion,
    val extractor: MarketObservationExtractorVersion,
    val summary: CalibrationSummary,
    val buys: List<BuyCalibration>,
)

/** Medians and maxima are null when no buy contributes a value; error bps are absolute values. */
data class CalibrationSummary(
    val buys: Int,
    val mathExact: Int,
    val mathMismatches: Int,
    val latencyChecked: Int,
    val noPriorState: Int,
    val unpredictable: Int,
    val medianErrorBps: BigDecimal?,
    val maxErrorBps: BigDecimal?,
    val medianTransactionFeeLamports: BigDecimal?,
    val medianOtherLamports: BigDecimal?,
    /** Buys per buy instruction name, sorted by name. */
    val instructions: Map<String, Int> = emptyMap(),
)

data class BuyCalibration(
    val signature: TransactionSignature,
    val slot: Slot,
    val mint: MintAddress,
    val solAmount: BigInteger,
    val instruction: PumpFunBuyInstruction,
    val math: CurveMathCheck,
    val latency: LatencyCheck,
    val costs: BuyCosts,
)

/** The buy's recorded amounts beside what the curve formula computes from its pre-trade reserves; null when it computes none. */
data class CurveMathCheck(
    val tokenAmount: BigInteger,
    val computedTokenAmount: BigInteger?,
    val feeLamports: BigInteger,
    val computedFeeLamports: BigInteger,
    val creatorFeeLamports: BigInteger,
    val computedCreatorFeeLamports: BigInteger,
) {
    val exact: Boolean
        get() = tokenAmount == computedTokenAmount && feeLamports == computedFeeLamports &&
            creatorFeeLamports == computedCreatorFeeLamports
}

/** What the buy would have received against the newest captured trade of its mint in an earlier slot. */
sealed interface LatencyCheck {
    data object NoPriorState : LatencyCheck

    /** [errorBps] is (actual − predicted) / predicted in basis points, at scale 2 HALF_EVEN. */
    data class Checked(
        val observationId: String,
        val observationSlot: Slot,
        val predictedTokenAmount: BigInteger,
        val errorBps: BigDecimal,
    ) : LatencyCheck

    data class Unpredictable(
        val observationId: String,
        val observationSlot: Slot,
        val reason: UnpredictableReason,
    ) : LatencyCheck
}

enum class UnpredictableReason {
    /** The buy would take more tokens than the prior state's real token reserves. */
    EXCEEDS_CURVE,

    /** The prior state yields no whole raw token for the buy's SOL, so no relative error exists. */
    ZERO_TOKENS,
}

/** [otherLamports] is what the wallet paid beyond the curve, its fees and the network fee: tips, rent and the like. */
data class BuyCosts(
    val transactionFeeLamports: BigInteger,
    val otherLamports: BigInteger,
)
