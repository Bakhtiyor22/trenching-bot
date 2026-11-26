package com.trenching.bot.notification.domain

import com.trenching.bot.notification.NotificationKind

/** A stored message waiting for delivery; [attempts] counts the sends already tried. */
data class PendingNotification(val id: String, val key: String, val kind: NotificationKind, val payload: String, val attempts: Int)
