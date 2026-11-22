package com.trenching.bot.notification

/**
 * Stores a message to be delivered later. It joins the caller's transaction, or runs its own short one when there is none, so a
 * rolled-back change never notifies. A repeat of the same [OutboundNotification.key] stores nothing and raises nothing. When
 * notifications are off, or the kind is not enabled, it does nothing.
 */
fun interface NotificationPublisher {
    fun publish(notification: OutboundNotification)

    companion object {
        /** What the application publishes through while notifications are not configured. */
        val DISABLED = NotificationPublisher { }
    }
}
