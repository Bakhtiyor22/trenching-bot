package com.trenching.bot.notification.application

import com.trenching.bot.notification.domain.NotificationPayload
import com.trenching.bot.notification.domain.RenderedLink
import com.trenching.bot.notification.domain.RenderedNotification

/** Joins each stored link path to the console address. Escaping and layout belong to the channel. */
class NotificationRenderer(consoleBaseUrl: String) {
    private val base = consoleBaseUrl.trimEnd('/')

    fun render(payload: NotificationPayload): RenderedNotification =
        RenderedNotification(payload.title, payload.lines, payload.links.map { RenderedLink(it.label, base + it.path) })
}
