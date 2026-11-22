package com.trenching.bot.notification

/**
 * One message for the operator, as plain text: the channel escapes it. [key] identifies the event the message reports, so
 * publishing it again changes nothing. [links] are ordered; the first is the main page of the subject.
 */
data class OutboundNotification(
    val key: String,
    val kind: NotificationKind,
    val title: String,
    val lines: List<String>,
    val links: List<NotificationLink>,
) {
    init {
        require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) { "A notification key has 1 to $MAX_KEY_LENGTH characters length=${key.length}" }
        require(title.isNotBlank() && title.length <= MAX_TITLE_LENGTH) { "A notification title has 1 to $MAX_TITLE_LENGTH characters length=${title.length}" }
    }

    companion object {
        const val MAX_KEY_LENGTH = 200
        const val MAX_TITLE_LENGTH = 256
    }
}
