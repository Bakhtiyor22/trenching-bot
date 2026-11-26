package com.trenching.bot.notification.application

import com.trenching.bot.notification.domain.DeliveryDecision
import com.trenching.bot.notification.domain.NewNotification
import com.trenching.bot.notification.domain.PendingNotification
import java.time.Instant

/** The outbox table behind the publisher and the dispatcher. Each function is one short transaction, or joins the caller's. */
interface NotificationOutboxStore {
    /** True when the row was stored, false when its key was already there. Never fails because of a repeat. */
    fun insertIfAbsent(notification: NewNotification): Boolean

    /** Pending rows whose next attempt is not after [now], oldest first, at most [limit]. */
    fun due(now: Instant, limit: Int): List<PendingNotification>

    /**
     * Counts one more attempt on a pending row that has [expectedAttempts] and holds it back until [nextAttemptAt], before the message is
     * sent. False when the row is gone, decided, or was claimed by someone else since it was read.
     */
    fun claim(id: String, expectedAttempts: Int, nextAttemptAt: Instant): Boolean

    /** Stores the result of attempt number [attempts] of row [id]; a row that no longer exists, or is already decided, is left alone. */
    fun record(id: String, attempts: Int, decision: DeliveryDecision, at: Instant)

    /** Deletes up to [limit] sent or failed rows created before [cutoff] and returns how many; a pending row is never deleted. */
    fun deleteCreatedBefore(cutoff: Instant, limit: Int): Int
}
