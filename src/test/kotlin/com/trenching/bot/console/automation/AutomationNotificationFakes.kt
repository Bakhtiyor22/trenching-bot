package com.trenching.bot.console.automation

import com.trenching.bot.notification.NotificationPublisher
import com.trenching.bot.notification.RecordingNotificationPublisher

/**
 * The publisher the automation tests' transactional beans publish through: a recording one by default, and whatever [use] sets
 * for a test that wants the real outbox. Not a [NotificationPublisher] itself, so the application's own publisher bean stays the
 * only one in the context. [reset] puts the recording one back, empty.
 */
class SwitchableNotifications {
    var recording = RecordingNotificationPublisher()
        private set

    private var target: NotificationPublisher = recording

    val publisher = NotificationPublisher { target.publish(it) }

    fun use(publisher: NotificationPublisher) {
        target = publisher
    }

    fun reset() {
        recording = RecordingNotificationPublisher()
        target = recording
    }
}
