package com.trenching.bot.walletactivity

import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.domain.WalletTransaction
import java.nio.file.Path

/**
 * A verified, complete capture decoded offline. [records] keeps the capture's selection order (newest first);
 * [transactions] is in canonical order: slot, then signature, then instruction index.
 */
data class WalletHistoryDecodeResult(
    val directory: Path,
    val wallet: WalletAddress,
    val selected: Int,
    val complete: Boolean,
    val decoder: WalletHistoryDecoderVersion,
    val transactions: List<WalletTransaction>,
    val records: List<WalletHistoryDecodeRecord>,
)

data class WalletHistoryDecodeRecord(
    val signature: TransactionSignature,
    val slot: Slot,
    val outcome: WalletHistoryDecodeOutcome,
)

enum class WalletHistoryDecoderVersion {
    PUMPFUN_BONDING_CURVE_BUY_V1,
}

enum class WalletHistoryDecodeOutcome {
    DECODED,
    NOT_SIGNER,
    FAILED,
    UNAVAILABLE,
    UNSUPPORTED_VERSION,
    WITHHELD_UNSAFE_TEXT,
    UNSUPPORTED_ROUTE,
    AMBIGUOUS,
}
