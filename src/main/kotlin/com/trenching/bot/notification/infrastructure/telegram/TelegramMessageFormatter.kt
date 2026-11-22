package com.trenching.bot.notification.infrastructure.telegram

import com.trenching.bot.notification.config.LinkStyle
import com.trenching.bot.notification.domain.RenderedLink
import com.trenching.bot.notification.domain.RenderedNotification

/**
 * The HTML text of one Telegram message: a bold title, the lines, a blank line, then the links. [LinkStyle.ANCHOR] shows every
 * link as an anchor; [LinkStyle.PLAIN] shows only the first, as a bare URL. A message over Telegram's 4,096 characters keeps the
 * lines that fit whole, then the first link, and ends with [MORE_IN_CONSOLE].
 */
class TelegramMessageFormatter(private val linkStyle: LinkStyle) {
    fun format(notification: RenderedNotification): String {
        val head = listOf("<b>${escapeText(notification.title)}</b>")
        val body = notification.lines.map(::escapeText)
        val links = linkLines(notification.links)
        val whole = head + body + linkBlock(links)
        if (joinedLength(whole) <= MAX_LENGTH) return whole.joinToString(SEPARATOR)

        val tail = linkBlock(links.take(1)) + MORE_IN_CONSOLE
        val kept = mutableListOf<String>()
        var length = joinedLength(head + tail)
        for (line in body) {
            length += line.length + SEPARATOR.length
            if (length > MAX_LENGTH) break
            kept += line
        }
        return (head + kept + tail).joinToString(SEPARATOR)
    }

    private fun linkLines(links: List<RenderedLink>): List<String> = when (linkStyle) {
        LinkStyle.ANCHOR -> links.map { "<a href=\"${escapeAttribute(it.url)}\">${escapeText(it.label)}</a>" }
        LinkStyle.PLAIN -> links.take(1).map { escapeText(it.url) }
    }

    private fun linkBlock(links: List<String>): List<String> = if (links.isEmpty()) emptyList() else listOf("") + links

    private fun joinedLength(lines: List<String>): Int = lines.sumOf { it.length } + SEPARATOR.length * (lines.size - 1)

    private fun escapeText(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttribute(value: String): String = escapeText(value).replace("\"", "&quot;")

    companion object {
        const val MAX_LENGTH = 4096
        const val MORE_IN_CONSOLE = "… more in the console"
        private const val SEPARATOR = "\n"
    }
}
