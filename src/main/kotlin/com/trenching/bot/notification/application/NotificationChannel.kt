package com.trenching.bot.notification.application

import com.trenching.bot.notification.domain.DeliveryResult
import com.trenching.bot.notification.domain.RenderedNotification

/** Where a message goes. Telegram is the only adapter; a channel answers with a [DeliveryResult] instead of throwing. */
fun interface NotificationChannel {
    fun send(notification: RenderedNotification): DeliveryResult
}
