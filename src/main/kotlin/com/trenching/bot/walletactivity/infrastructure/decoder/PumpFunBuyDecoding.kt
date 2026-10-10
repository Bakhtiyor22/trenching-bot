package com.trenching.bot.walletactivity.infrastructure.decoder

import com.trenching.bot.walletactivity.PumpFunBuyFacts
import com.trenching.bot.walletactivity.WalletHistoryDecodeOutcome
import com.trenching.bot.walletactivity.domain.WalletTransaction

internal sealed interface PumpFunBuyDecoding {
    data class Decoded(val transaction: WalletTransaction, val facts: PumpFunBuyFacts) : PumpFunBuyDecoding
    /** [instructions] names the Pump.fun instructions the transaction contained, for capture summaries. */
    data class Rejected(val reason: PumpFunBuyRejection, val instructions: Set<String>) : PumpFunBuyDecoding
}

internal enum class PumpFunBuyRejection(val outcome: WalletHistoryDecodeOutcome) {
    NO_BUY(WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE),
    NO_TRADE_EVENT(WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE),
    SELL_EVENT(WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE),

    /** The curve is quoted in another token: the event's SOL amount and virtual SOL reserves are both zero. */
    NON_SOL_QUOTE(WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE),
    MALFORMED_EVIDENCE(WalletHistoryDecodeOutcome.AMBIGUOUS),
    MULTIPLE_BUYS(WalletHistoryDecodeOutcome.AMBIGUOUS),
    MULTIPLE_TRADE_EVENTS(WalletHistoryDecodeOutcome.AMBIGUOUS),
    OTHER_USER(WalletHistoryDecodeOutcome.AMBIGUOUS),
    INSTRUCTION_MISMATCH(WalletHistoryDecodeOutcome.AMBIGUOUS),
    AMOUNT_MISMATCH(WalletHistoryDecodeOutcome.AMBIGUOUS),
    ZERO_AMOUNT(WalletHistoryDecodeOutcome.AMBIGUOUS),
    MISSING_BALANCE(WalletHistoryDecodeOutcome.AMBIGUOUS),
    BALANCE_MISMATCH(WalletHistoryDecodeOutcome.AMBIGUOUS),
    DECIMALS_MISMATCH(WalletHistoryDecodeOutcome.AMBIGUOUS),
}
