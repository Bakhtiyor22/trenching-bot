package com.trenching.bot.walletactivity

import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.domain.WalletTransaction
import java.nio.file.Path

/**
 * A verified, complete capture decoded offline. [records] keeps the capture's selection order (newest first);
 * [transactions] is in canonical order: slot, then signature, then instruction index. [buyFacts] holds one entry per
 * decoded buy, ordered by slot, then signature.
 */
data class WalletHistoryDecodeResult(
    val directory: Path,
    val wallet: WalletAddress,
    val selected: Int,
    val complete: Boolean,
    val decoder: WalletHistoryDecoderVersion,
    val transactions: List<WalletTransaction>,
    val records: List<WalletHistoryDecodeRecord>,
    val buyFacts: List<PumpFunBuyFacts> = emptyList(),
    /** For rejected signer transactions: how many contained each Pump.fun instruction name, sorted by name. */
    val rejectedInstructions: Map<String, Int> = emptyMap(),
    /** For rejected signer transactions: how many were rejected for each reason, sorted by reason. */
    val rejections: Map<String, Int> = emptyMap(),
)

data class WalletHistoryDecodeRecord(
    val signature: TransactionSignature,
    val slot: Slot,
    val outcome: WalletHistoryDecodeOutcome,
)

enum class WalletHistoryDecoderVersion {
    /** Legacy `buy` only. */
    PUMPFUN_BONDING_CURVE_BUY_V1,

    /** Legacy `buy` and SOL-quoted `buy_exact_quote_in_v3`. */
    PUMPFUN_BONDING_CURVE_BUY_V2,
}

enum class WalletHistoryDecodeOutcome {
    DECODED,
    NOT_SIGNER,
    FAILED,
    UNAVAILABLE,
    UNSUPPORTED_VERSION,
    WITHHELD_UNSAFE_TEXT,
    WALLET_ABSENT,
    UNSUPPORTED_ROUTE,
    AMBIGUOUS,
}
