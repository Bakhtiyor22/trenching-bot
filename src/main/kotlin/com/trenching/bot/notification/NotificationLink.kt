package com.trenching.bot.notification

/** A console page a message points to: [path] starts with `/` and is joined to the configured console address when the message is sent. */
data class NotificationLink(val label: String, val path: String) {
    init {
        require(label.isNotBlank()) { "A notification link needs a label" }
        require(path.startsWith("/")) { "A notification link path starts with / path=$path" }
    }
}
