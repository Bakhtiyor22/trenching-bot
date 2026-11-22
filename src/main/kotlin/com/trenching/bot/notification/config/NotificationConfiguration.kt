package com.trenching.bot.notification.config

import com.trenching.bot.config.SimulationClock
import com.trenching.bot.notification.NotificationPublisher
import com.trenching.bot.notification.application.NotificationOutboxStore
import com.trenching.bot.notification.application.OutboxNotificationPublisher
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Exists in every profile: the publisher is a no-op, and says so at startup, while Telegram is not configured. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties::class)
class NotificationConfiguration {
    @Bean
    fun notificationPublisher(
        properties: NotificationProperties,
        store: NotificationOutboxStore,
        @Qualifier(SimulationClock.NAME) clock: Clock,
    ): NotificationPublisher =
        if (properties.enabled) OutboxNotificationPublisher(store, clock, properties.kinds) else NotificationPublisher.DISABLED

    @Bean
    fun notificationStartupLog(properties: NotificationProperties): NotificationStartupLog = NotificationStartupLog(properties)
}
