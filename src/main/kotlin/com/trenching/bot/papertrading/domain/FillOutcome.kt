package com.trenching.bot.papertrading.domain

sealed interface FillOutcome {
    data class Filled(val fill: PaperFill) : FillOutcome
    data class Rejected(val orderId: String, val reason: RejectionReason) : FillOutcome
}

/** Extension accessor leaves the stored report JSON shape unchanged. */
val FillOutcome.orderId: String
    get() = when (this) {
        is FillOutcome.Filled -> fill.orderId
        is FillOutcome.Rejected -> orderId
    }

enum class RejectionReason {
    MISSING_OBSERVATION, STALE_OBSERVATION, INVALID_QUALITY, INSUFFICIENT_LIQUIDITY, ALREADY_OPEN, ZERO_QUANTITY
}
