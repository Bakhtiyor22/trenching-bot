package com.trenching.bot.notification.infrastructure.telegram

import com.trenching.bot.apiusage.ApiCall
import com.trenching.bot.apiusage.ApiCallOutcome
import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.common.ExceptionCauses
import com.trenching.bot.notification.application.NotificationChannel
import com.trenching.bot.notification.config.NotificationProperties
import com.trenching.bot.notification.domain.DeliveryCategory
import com.trenching.bot.notification.domain.DeliveryResult
import com.trenching.bot.notification.domain.RenderedNotification
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.TimeoutException
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/**
 * Sends a message to one Telegram chat with the Bot API's `sendMessage`. The bot token is part of the request URL, so the URL is
 * built once, never logged or stored, and a failure is reported by category and exception class only, never by its message,
 * which quotes the URL. Every request is counted once as a Telegram `sendMessage` call.
 */
class TelegramNotificationChannel(
    private val restClient: RestClient,
    private val settings: NotificationProperties.Telegram,
    private val recorder: ApiUsageRecorder,
) : NotificationChannel {
    private val log = LoggerFactory.getLogger(TelegramNotificationChannel::class.java)
    private val formatter = TelegramMessageFormatter(settings.linkStyle)
    private val uri: URI = URI.create("${settings.apiBaseUrl.trimEnd('/')}/bot${settings.botToken}/$METHOD")

    override fun send(notification: RenderedNotification): DeliveryResult {
        val reply = try {
            restClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(requestBody(notification))
                .exchange { _, response -> Reply(response.statusCode.value(), response.bodyTo(ByteArray::class.java) ?: EMPTY_BODY) }
        } catch (exception: RestClientException) {
            recorder.record(call(ApiCallOutcome.FAILED, 0))
            val category = classify(exception)
            log.debug("Telegram did not answer. code={} cause={}", category, ExceptionCauses.rootName(exception))
            return DeliveryResult.RetryLater(category, null)
        }
        val result = resultOf(reply)
        recorder.record(call(outcome(reply.status), reply.body.size.toLong()))
        if (result != DeliveryResult.Delivered) log.debug("Telegram did not deliver the message. status={} result={}", reply.status, result)
        return result
    }

    private fun requestBody(notification: RenderedNotification): String = JSON.writeValueAsString(
        mapOf(
            "chat_id" to settings.chatId,
            "text" to formatter.format(notification),
            "parse_mode" to "HTML",
            "link_preview_options" to mapOf("is_disabled" to true),
        ),
    )

    private fun resultOf(reply: Reply): DeliveryResult = when (reply.status) {
        STATUS_OK -> DeliveryResult.Delivered
        STATUS_TOO_MANY_REQUESTS -> DeliveryResult.RetryLater(DeliveryCategory.RATE_LIMITED, retryAfter(reply.body))
        STATUS_BAD_REQUEST -> DeliveryResult.Rejected(DeliveryCategory.BAD_REQUEST)
        STATUS_UNAUTHORIZED -> DeliveryResult.Rejected(DeliveryCategory.UNAUTHORIZED)
        STATUS_FORBIDDEN -> DeliveryResult.Rejected(DeliveryCategory.FORBIDDEN)
        in CLIENT_ERRORS -> DeliveryResult.Rejected(DeliveryCategory.REJECTED)
        else -> DeliveryResult.RetryLater(DeliveryCategory.UNAVAILABLE, null)
    }

    /** The wait in `parameters.retry_after` seconds that Telegram sends with a 429; null when the body has none. */
    private fun retryAfter(body: ByteArray): Duration? = try {
        JSON.readTree(body).path("parameters").path("retry_after").takeIf { it.isIntegralNumber }?.asLong()?.takeIf { it >= 0 }?.let(Duration::ofSeconds)
    } catch (_: JacksonException) {
        null
    }

    private fun outcome(status: Int): ApiCallOutcome = when (status) {
        STATUS_OK -> ApiCallOutcome.OK
        STATUS_TOO_MANY_REQUESTS -> ApiCallOutcome.RATE_LIMITED
        else -> ApiCallOutcome.FAILED
    }

    private fun classify(exception: RestClientException): DeliveryCategory {
        val causes = ExceptionCauses.chain(exception)
        return if (causes.any { it is HttpTimeoutException || it is SocketTimeoutException || it is TimeoutException }) DeliveryCategory.TIMEOUT
        else DeliveryCategory.UNAVAILABLE
    }

    private fun call(outcome: ApiCallOutcome, responseBytes: Long = 0) =
        ApiCall(ApiProvider.TELEGRAM, METHOD, USAGE, outcome, responseBytes)

    private class Reply(val status: Int, val body: ByteArray)

    private companion object {
        const val METHOD = "sendMessage"
        const val STATUS_OK = 200
        const val STATUS_BAD_REQUEST = 400
        const val STATUS_UNAUTHORIZED = 401
        const val STATUS_FORBIDDEN = 403
        const val STATUS_TOO_MANY_REQUESTS = 429
        val CLIENT_ERRORS = 400..499
        val USAGE = ApiUsageContext(ApiTask.NOTIFICATION, null)
        val EMPTY_BODY = ByteArray(0)
        val JSON: JsonMapper = JsonMapper.builder().build()
    }
}
