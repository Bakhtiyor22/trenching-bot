package com.trenching.bot.papertrading.domain

sealed interface FillOutcome {
    data class Filled(val fill: PaperFill) : FillOutcome
    data class Rejected(val orderId: String, val reason: RejectionReason) : FillOutcome
}

enum class RejectionReason {
    MISSING_OBSERVATION, STALE_OBSERVATION, INVALID_QUALITY, INSUFFICIENT_LIQUIDITY, ALREADY_OPEN, ZERO_QUANTITY
}
