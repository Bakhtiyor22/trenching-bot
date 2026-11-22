package com.trenching.bot.notification.infrastructure.schedule

import com.trenching.bot.notification.application.NotificationDispatcher
import org.springframework.scheduling.annotation.Scheduled

/** The dispatcher's timed trigger; the first run is one interval after startup. */
class NotificationDispatchScheduler(private val dispatcher: NotificationDispatcher) {
    @Scheduled(fixedDelayString = DISPATCH_INTERVAL, initialDelayString = DISPATCH_INTERVAL)
    fun dispatch() {
        dispatcher.dispatch()
    }

    private companion object {
        const val DISPATCH_INTERVAL = "\${trenching.notification.dispatch-interval:30s}"
    }
}
