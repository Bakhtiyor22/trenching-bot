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

/**
 * Why a dip cycle bought nothing, or (for the last three) why its position stayed open; per the dip-entry-strategy ADR.
 */
enum class RejectionReason {
    NO_DIP,
    DIP_BELOW_MIN_MARKET_CAP,
    MIGRATED_BEFORE_ENTRY,
    UNSUPPORTED_SUPPLY,
    NO_FILL_AFTER_TRIGGER,
    EXCEEDS_CURVE,
    INVALID_QUALITY,
    ZERO_QUANTITY,
    HORIZON_REACHED,
    MIGRATED,
    NO_EXIT_FILL,
    ;

    companion object {
        /** Reasons an order is rejected; the rest only ever explain an open position's missing exit. */
        val ENTRY: List<RejectionReason> = entries - setOf(HORIZON_REACHED, MIGRATED, NO_EXIT_FILL)
    }
}
