package com.trenching.bot.notification.application

import com.trenching.bot.notification.NotificationKind
import com.trenching.bot.notification.NotificationPublisher
import com.trenching.bot.notification.OutboundNotification
import com.trenching.bot.notification.domain.NewNotification
import com.trenching.bot.notification.domain.NotificationId
import com.trenching.bot.notification.domain.NotificationPayload
import com.trenching.bot.notification.mapper.NotificationPayloadCodec
import java.time.Clock
import org.slf4j.LoggerFactory

/** Writes a message to the outbox in the caller's transaction, once per key, and only for an enabled kind. */
class OutboxNotificationPublisher(
    private val store: NotificationOutboxStore,
    private val clock: Clock,
    private val kinds: Set<NotificationKind>,
) : NotificationPublisher {
    private val log = LoggerFactory.getLogger(OutboxNotificationPublisher::class.java)

    override fun publish(notification: OutboundNotification) {
        if (notification.kind !in kinds) return
        val payload = NotificationPayloadCodec.encode(NotificationPayload(notification.title, notification.lines, notification.links))
        val stored = store.insertIfAbsent(
            NewNotification(NotificationId.of(notification.key), notification.key, notification.kind, payload, clock.instant()),
        )
        if (!stored) log.debug("Notification already stored. kind={} notificationKey={}", notification.kind, notification.key)
    }
}
