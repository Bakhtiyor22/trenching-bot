package com.trenching.bot.walletactivity.domain

sealed interface NormalizationOutcome {
    data class Accepted(val trade: WalletTradeObserved) : NormalizationOutcome
    data class Ignored(val sourceEventId: String, val reason: IngestionReason) : NormalizationOutcome
}

enum class IngestionReason { FAILED, UNSUPPORTED_ROUTE, UNSUPPORTED_QUOTE, OTHER_WALLET, NOT_SIGNER, NOT_CONFIRMED, INVALID_DELTAS }
