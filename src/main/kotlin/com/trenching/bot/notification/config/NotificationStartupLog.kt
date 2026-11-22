package com.trenching.bot.notification.config

import com.trenching.bot.common.LogDurations
import com.trenching.bot.common.LogLine
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

/** Says once, when the application is ready, whether messages are stored and sent. Never the token or the chat. */
class NotificationStartupLog(private val properties: NotificationProperties) {
    private val log = LoggerFactory.getLogger(NotificationStartupLog::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun logSettings() {
        if (!properties.enabled) {
            LogLine("Notifications are off: no Telegram bot token and chat are set, so no message is stored or sent.").field("enabled", false).info(log)
            return
        }
        LogLine("Notifications are on: messages are stored and sent to one Telegram chat every {}.", LogDurations.compact(properties.dispatchInterval))
            .field("enabled", true).field("kinds", properties.kinds.sorted().joinToString(","))
            .field("linkStyle", properties.telegram.linkStyle).field("consoleBaseUrl", properties.consoleBaseUrl)
            .field("maxAttempts", properties.maxAttempts).field("retention", LogDurations.compact(properties.retention)).info(log)
    }
}
