package com.trenching.bot.marketdata.domain

import com.trenching.bot.common.Commitment

/** Why an observation cannot stand for a tradeable price. */
enum class ObservationIssue { INVALID_QUALITY, STALE }

/** The quality rules every price a run uses must pass, whether it triggers, fills or exits. */
object ObservationEligibility {
    fun issue(observation: MarketObservation, maxAgeSlots: Long): ObservationIssue? = when {
        observation.price.signum() <= 0 || observation.quality == ObservationQuality.INVALID ||
            observation.commitment == Commitment.PROCESSED || observation.priceSlot > observation.slot -> ObservationIssue.INVALID_QUALITY
        observation.slot.value - observation.priceSlot.value > maxAgeSlots -> ObservationIssue.STALE
        else -> null
    }
}
