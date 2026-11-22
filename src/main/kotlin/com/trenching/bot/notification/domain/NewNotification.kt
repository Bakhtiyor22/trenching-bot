package com.trenching.bot.notification.domain

import com.trenching.bot.notification.NotificationKind
import java.time.Instant

/** A message to store; [payload] is the JSON text of its [NotificationPayload]. */
data class NewNotification(val id: String, val key: String, val kind: NotificationKind, val payload: String, val createdAt: Instant)
