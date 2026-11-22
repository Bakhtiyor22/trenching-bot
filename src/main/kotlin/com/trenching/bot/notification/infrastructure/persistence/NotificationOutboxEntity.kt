package com.trenching.bot.notification.infrastructure.persistence

import com.trenching.bot.notification.NotificationKind
import com.trenching.bot.notification.domain.DeliveryCategory
import com.trenching.bot.notification.domain.NotificationState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import org.hibernate.annotations.ColumnDefault

/** One message waiting for, or past, delivery. Rows are inserted by [NotificationOutboxRepository.insertIfAbsent] and only updated after. */
@Entity
@Table(
    name = "notification_outbox",
    uniqueConstraints = [UniqueConstraint(name = "uq_notification_outbox_key", columnNames = ["notification_key"])],
    indexes = [Index(name = "idx_notification_outbox_due", columnList = "state, next_attempt_at")],
)
class NotificationOutboxEntity(
    @Id @Column(name = "id", nullable = false, length = 64) var id: String,
    @Column(name = "notification_key", nullable = false, length = 200) var notificationKey: String,
    @Enumerated(EnumType.STRING) @Column(name = "kind", nullable = false, length = 32) var kind: NotificationKind,
    @Column(name = "payload", nullable = false, columnDefinition = "text") var payload: String,
    @Enumerated(EnumType.STRING) @Column(name = "state", nullable = false, length = 16) var state: NotificationState,
    @ColumnDefault("0") @Column(name = "attempts", nullable = false) var attempts: Int,
    @Column(name = "next_attempt_at", nullable = false) var nextAttemptAt: Instant,
    @Enumerated(EnumType.STRING) @Column(name = "last_error", length = 64) var lastError: DeliveryCategory?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "sent_at") var sentAt: Instant?,
)
