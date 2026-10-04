package com.trenching.bot.walletactivity.infrastructure.decoder

import com.trenching.bot.walletactivity.WalletHistoryDecodeOutcome
import com.trenching.bot.walletactivity.domain.WalletTransaction

internal sealed interface PumpFunBuyDecoding {
    data class Decoded(val transaction: WalletTransaction) : PumpFunBuyDecoding
    data class Rejected(val reason: PumpFunBuyRejection) : PumpFunBuyDecoding
}

internal enum class PumpFunBuyRejection(val outcome: WalletHistoryDecodeOutcome) {
    NO_BUY(WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE),
    NO_TRADE_EVENT(WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE),
    SELL_EVENT(WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE),
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
