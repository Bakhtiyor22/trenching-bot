package com.trenching.bot.notification.config

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Registers the annotated configuration only where a Telegram bot token is set. A blank value, which the environment default
 * `${TELEGRAM_BOT_TOKEN:}` gives, counts as not set, so `@ConditionalOnProperty` cannot say it.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(NotificationsConfiguredCondition::class)
annotation class ConditionalOnNotificationsConfigured

class NotificationsConfiguredCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        !context.environment.getProperty(NotificationProperties.BOT_TOKEN_PROPERTY).isNullOrBlank()
}
