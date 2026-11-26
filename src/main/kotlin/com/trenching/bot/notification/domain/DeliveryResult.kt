package com.trenching.bot.notification.domain

import java.time.Duration

/** What a channel answers for one message; a channel reports its failures this way instead of throwing. */
sealed interface DeliveryResult {
    data object Delivered : DeliveryResult

    /** [after] is the wait the provider asked for, when it did. */
    data class RetryLater(val category: DeliveryCategory, val after: Duration?) : DeliveryResult

    data class Rejected(val category: DeliveryCategory) : DeliveryResult
}
