package com.trenching.bot.notification.domain

import com.trenching.bot.notification.NotificationLink

/** What is stored of a message besides its key and kind. */
data class NotificationPayload(val title: String, val lines: List<String>, val links: List<NotificationLink>)
