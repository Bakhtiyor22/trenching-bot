package com.trenching.bot.notification.config

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.notification.application.DispatchPause
import com.trenching.bot.notification.application.NotificationChannel
import com.trenching.bot.notification.application.NotificationDispatcher
import com.trenching.bot.notification.application.NotificationOutboxStore
import com.trenching.bot.notification.application.NotificationRenderer
import com.trenching.bot.notification.domain.NotificationRetryPolicy
import com.trenching.bot.notification.infrastructure.schedule.NotificationDispatchScheduler
import com.trenching.bot.notification.infrastructure.telegram.TelegramNotificationChannel
import com.trenching.bot.notification.infrastructure.telegram.TelegramRestClients
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * The delivery side, present only while Telegram is configured. It turns scheduling on itself, because the automation's own
 * `@EnableScheduling` exists only when the automation does; a single application instance is assumed.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnNotificationsConfigured
@EnableScheduling
class NotificationDeliveryConfiguration {
    @Bean
    fun notificationChannel(properties: NotificationProperties, recorder: ApiUsageRecorder): NotificationChannel =
        TelegramNotificationChannel(TelegramRestClients.create(TelegramRestClients.CONNECT_TIMEOUT, TelegramRestClients.READ_TIMEOUT), properties.telegram, recorder)

    @Bean
    fun notificationDispatcher(
        properties: NotificationProperties,
        store: NotificationOutboxStore,
        channel: NotificationChannel,
        @Qualifier(SimulationClock.NAME) clock: Clock,
    ): NotificationDispatcher = NotificationDispatcher(
        store, channel, NotificationRenderer(properties.consoleBaseUrl), NotificationRetryPolicy(properties.maxAttempts),
        properties.retention, clock, DispatchPause.ONE_SECOND,
    )

    @Bean
    fun notificationDispatchScheduler(dispatcher: NotificationDispatcher): NotificationDispatchScheduler =
        NotificationDispatchScheduler(dispatcher)
}
