package com.trenching.bot.notification.config

import com.trenching.bot.notification.NotificationKind
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/** How a Telegram message shows its links: every one as an HTML anchor, or only the first as a plain URL on its own line. */
enum class LinkStyle { ANCHOR, PLAIN }

/**
 * Outbound notifications (`docs/plans/20261122-telegram-alerts-and-backup-rpc-account.md`). They are on only when the Telegram bot
 * token and chat id are both set; with neither set nothing is stored or sent. One of the two alone fails startup, naming the
 * missing property. [consoleBaseUrl] is what link paths are joined to. The dispatcher runs every [dispatchInterval], gives up on a
 * message after [maxAttempts] sends, and deletes messages older than [retention]. Only the [kinds] listed are stored.
 *
 * Not a data class on purpose: the token must never reach a `toString`.
 */
@ConfigurationProperties(NotificationProperties.PREFIX)
class NotificationProperties(
    val telegram: Telegram = Telegram(),
    val consoleBaseUrl: String = DEFAULT_CONSOLE_BASE_URL,
    val dispatchInterval: Duration = Duration.ofSeconds(30),
    val maxAttempts: Int = 54,
    val retention: Duration = Duration.ofDays(30),
    val kinds: Set<NotificationKind> = NotificationKind.entries.toSet(),
) {
    init {
        require(consoleBaseUrl.startsWith("http://") || consoleBaseUrl.startsWith("https://")) { "$PREFIX.console-base-url must be an http or https address" }
        require(dispatchInterval >= Duration.ofSeconds(1)) { "$PREFIX.dispatch-interval must be at least 1 second" }
        require(maxAttempts >= 1) { "$PREFIX.max-attempts must be at least 1" }
        require(retention >= Duration.ofDays(1)) { "$PREFIX.retention must be at least 1 day" }
    }

    val enabled: Boolean get() = telegram.configured

    /** [botToken] is a secret and is part of the request URL; [apiBaseUrl] exists so tests can point at a local fake. */
    class Telegram(
        val botToken: String = "",
        val chatId: String = "",
        val linkStyle: LinkStyle = LinkStyle.ANCHOR,
        val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    ) {
        init {
            require(botToken.isBlank() || chatId.isNotBlank()) { "$PREFIX.telegram.chat-id is required when $PREFIX.telegram.bot-token is set" }
            require(chatId.isBlank() || botToken.isNotBlank()) { "$PREFIX.telegram.bot-token is required when $PREFIX.telegram.chat-id is set" }
            require(botToken.isBlank() || BOT_TOKEN.matches(botToken)) { "$PREFIX.telegram.bot-token has characters a Telegram token never has" }
            require(chatId.isBlank() || CHAT_ID.matches(chatId)) { "$PREFIX.telegram.chat-id must be a number or an @channel name" }
            require(isAllowedApiBase(apiBaseUrl)) { "$PREFIX.telegram.api-base-url must be an https address, or an http one on this machine, without user info" }
        }

        val configured: Boolean get() = botToken.isNotBlank()

        /** Checked on the address alone, before the token is joined to it, so no startup error can quote the token. */
        private fun isAllowedApiBase(url: String): Boolean {
            val uri = try {
                URI(url)
            } catch (_: URISyntaxException) {
                return false
            }
            val host = uri.host?.lowercase() ?: return false
            if (uri.userInfo != null) return false
            return when (uri.scheme?.lowercase()) {
                "https" -> true
                "http" -> host in LOCAL_HOSTS
                else -> false
            }
        }

        private companion object {
            val BOT_TOKEN = Regex("[A-Za-z0-9:_-]+")
            val CHAT_ID = Regex("-?\\d+|@\\w+")
            val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")
        }
    }

    companion object {
        const val PREFIX = "trenching.notification"
        const val BOT_TOKEN_PROPERTY = "$PREFIX.telegram.bot-token"
        const val DEFAULT_CONSOLE_BASE_URL = "http://localhost:8081"
        const val DEFAULT_API_BASE_URL = "https://api.telegram.org"
    }
}
