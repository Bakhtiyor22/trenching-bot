package com.trenching.bot.replay.domain

import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.strategy.domain.DipWatchOutcome
import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.TokenAccountFirstBuy
import java.math.BigDecimal

/**
 * What a wallet + token capture shows, per the wallet-token capture plan: whether the capture is complete, both first-buy
 * results, and what the dip-entry replay of its exported dataset did. [entry] is set only after a triggered dip, [exit] only
 * after a filled entry. The two first-buy results are null until the wallet capture is complete and decoded.
 */
data class TargetTokenVerdict(
    val classification: CaseClassification,
    val capture: CaptureVerdict,
    val tokenAccountFirstBuy: TokenAccountFirstBuy?,
    val actualFirstBuy: ActualFirstBuy?,
    val dip: DipVerdict,
    val entry: EntryVerdict?,
    val exit: ExitVerdict?,
    val replay: ReplayVerdict,
    val provenance: VerdictProvenance,
)

enum class CaseClassification {
    QUALIFYING_DIP,
    NEGATIVE_CASE,
    INCOMPLETE_CAPTURE,
    FIRST_BUY_UNPROVEN,
    FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT,
    NO_TARGET_BUY,
    REPLAY_FAILED,
}

/**
 * [stage] and [reason] name the first capture stage that is not ready; both are null for a complete capture, except that an
 * exported capture whose qualification has not run yet names the export stage with [VerdictReasons.QUALIFICATION_PENDING].
 */
data class CaptureVerdict(
    val state: CaptureVerdictState,
    val stage: CaptureVerdictStage? = null,
    val reason: String? = null,
)

enum class CaptureVerdictState { COMPLETE, INCOMPLETE, UNUSABLE }

enum class CaptureVerdictStage { WALLET, MARKET, RATES, EXPORT }

sealed interface DipVerdict {
    data class Qualified(
        val triggerSlot: Slot,
        val triggerPrice: BigDecimal,
        val marketCapUsd: BigDecimal,
        val rate: BigDecimal,
        val observationId: String,
    ) : DipVerdict

    data class NotQualified(val outcome: DipWatchOutcome) : DipVerdict

    /** The dip was never judged: no replay ran, it failed, or no buy started a cycle ([reason] is then its ingestion reason). */
    data class NotEvaluated(val reason: String) : DipVerdict
}

sealed interface EntryVerdict {
    data class Filled(val slot: Slot, val executionPrice: BigDecimal, val observationId: String) : EntryVerdict

    data class NotFilled(val reason: RejectionReason) : EntryVerdict
}

sealed interface ExitVerdict {
    data class Closed(val reason: CloseReason, val slot: Slot, val realizedPnl: BigDecimal) : ExitVerdict

    data class Open(val reason: RejectionReason) : ExitVerdict
}

sealed interface ReplayVerdict {
    data object Ran : ReplayVerdict

    data class Failed(val category: String) : ReplayVerdict

    data object NotRun : ReplayVerdict
}

/**
 * Which run produced the verdict: unset when no replay ran. [referencePrice] is the cycle's P0 and the first buy is the one
 * the cycle started from; before a replay the first buy is set only when it is proven.
 */
data class VerdictProvenance(
    val datasetChecksum: String? = null,
    val runId: String? = null,
    val configurationHash: String? = null,
    val engineVersion: String? = null,
    val referencePrice: BigDecimal? = null,
    val firstBuySignature: TransactionSignature? = null,
    val firstBuySlot: Slot? = null,
)
