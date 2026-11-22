package com.trenching.bot.notification.domain

import java.time.Instant

/** What happens to a stored message after one delivery attempt. */
sealed interface DeliveryDecision {
    data object Sent : DeliveryDecision

    data class RetryAt(val at: Instant, val category: DeliveryCategory) : DeliveryDecision

    data class Abandon(val category: DeliveryCategory) : DeliveryDecision
}
