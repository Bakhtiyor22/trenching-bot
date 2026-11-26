package com.trenching.bot.notification.infrastructure.persistence

import com.trenching.bot.notification.application.NotificationOutboxStore
import com.trenching.bot.notification.domain.DeliveryDecision
import com.trenching.bot.notification.domain.NewNotification
import com.trenching.bot.notification.domain.NotificationState
import com.trenching.bot.notification.domain.PendingNotification
import java.time.Instant
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class JpaNotificationOutboxStore(private val repository: NotificationOutboxRepository) : NotificationOutboxStore {
    /** Joins the caller's transaction, or starts its own, so a publish rolls back with the change it reports. */
    @Transactional
    override fun insertIfAbsent(notification: NewNotification): Boolean =
        repository.insertIfAbsent(
            notification.id, notification.key, notification.kind.name, notification.payload, NotificationState.PENDING.name, notification.createdAt,
        ) == 1

    @Transactional(readOnly = true)
    override fun due(now: Instant, limit: Int): List<PendingNotification> =
        repository.findByStateAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(NotificationState.PENDING, now, PageRequest.of(0, limit))
            .map { PendingNotification(it.id, it.notificationKey, it.kind, it.payload, it.attempts) }

    @Transactional
    override fun claim(id: String, expectedAttempts: Int, nextAttemptAt: Instant): Boolean {
        val row = repository.findById(id).orElse(null)?.takeIf { it.state == NotificationState.PENDING && it.attempts == expectedAttempts } ?: return false
        row.attempts = expectedAttempts + 1
        row.nextAttemptAt = nextAttemptAt
        return true
    }

    @Transactional
    override fun record(id: String, attempts: Int, decision: DeliveryDecision, at: Instant) {
        val row = repository.findById(id).orElse(null)?.takeIf { it.state == NotificationState.PENDING } ?: return
        row.attempts = attempts
        when (decision) {
            DeliveryDecision.Sent -> {
                row.state = NotificationState.SENT
                row.sentAt = at
                row.lastError = null
            }
            is DeliveryDecision.RetryAt -> {
                row.nextAttemptAt = decision.at
                row.lastError = decision.category
            }
            is DeliveryDecision.Abandon -> {
                row.state = NotificationState.FAILED
                row.lastError = decision.category
            }
        }
    }

    @Transactional
    override fun deleteCreatedBefore(cutoff: Instant, limit: Int): Int = repository.deleteCreatedBefore(cutoff, limit)
}
