package com.trenching.bot.notification.domain

import java.time.Duration
import java.time.Instant

/**
 * Turns one delivery result into the next step. A retry waits the time the provider stated, at most 24 hours, or doubles from 30
 * seconds up to 30 minutes; the [maxAttempts]th attempt that still asks to retry gives up, and so does any rejection.
 */
class NotificationRetryPolicy(val maxAttempts: Int) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    /** [attempt] counts this attempt, so it is 1 for a message sent for the first time. */
    fun decide(attempt: Int, result: DeliveryResult, now: Instant): DeliveryDecision = when (result) {
        DeliveryResult.Delivered -> DeliveryDecision.Sent
        is DeliveryResult.Rejected -> DeliveryDecision.Abandon(result.category)
        is DeliveryResult.RetryLater ->
            if (attempt >= maxAttempts) DeliveryDecision.Abandon(result.category)
            else DeliveryDecision.RetryAt(now.plus(result.after?.coerceAtMost(MAX_STATED_WAIT) ?: backoff(attempt)), result.category)
    }

    /** The time a row is held back while attempt number [attempt] is under way, so a failure to store its outcome repeats it with backoff. */
    fun claimUntil(attempt: Int, now: Instant): Instant = now.plus(backoff(attempt))

    private fun backoff(attempt: Int): Duration {
        val doublings = (attempt - 1).coerceAtMost(MAX_DOUBLINGS)
        return INITIAL_BACKOFF.multipliedBy(1L shl doublings).coerceAtMost(MAX_BACKOFF)
    }

    private companion object {
        val INITIAL_BACKOFF: Duration = Duration.ofSeconds(30)
        val MAX_BACKOFF: Duration = Duration.ofMinutes(30)
        val MAX_STATED_WAIT: Duration = Duration.ofHours(24)
        const val MAX_DOUBLINGS = 10
    }
}
