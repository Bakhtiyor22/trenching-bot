package com.trenching.bot.notification

import ch.qos.logback.classic.Logger
import com.sun.net.httpserver.HttpServer
import com.trenching.bot.apiusage.ApiCall
import com.trenching.bot.apiusage.ApiCallOutcome
import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiPurpose
import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.notification.config.LinkStyle
import com.trenching.bot.notification.config.NotificationProperties
import com.trenching.bot.notification.domain.DeliveryCategory
import com.trenching.bot.notification.domain.DeliveryResult
import com.trenching.bot.notification.domain.RenderedLink
import com.trenching.bot.notification.domain.RenderedNotification
import com.trenching.bot.notification.infrastructure.telegram.TelegramNotificationChannel
import com.trenching.bot.notification.infrastructure.telegram.TelegramRestClients
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class TelegramNotificationChannelTest {
    private val server = FakeTelegramServer.start()
    private val calls = mutableListOf<ApiCall>()
    private val logs = LogCapture(Logger.ROOT_LOGGER_NAME, ignoring = listOf(FAKE_SERVER_LOGGER))

    @AfterTest
    fun stop() {
        server.close()
        logs.close()
    }

    private fun channel(
        style: LinkStyle = LinkStyle.ANCHOR,
        readTimeout: Duration = Duration.ofSeconds(5),
        connectTimeout: Duration = Duration.ofSeconds(1),
    ) = TelegramNotificationChannel(
        TelegramRestClients.create(connectTimeout, readTimeout),
        NotificationProperties.Telegram(botToken = TOKEN, chatId = CHAT_ID, linkStyle = style, apiBaseUrl = server.baseUrl),
        ApiUsageRecorder { calls += it },
    )

    private fun message(vararg lines: String, links: List<RenderedLink> = LINKS) = RenderedNotification("Buy 7Xyz", lines.toList(), links)

    @Test
    fun `posts the message as html to the sendMessage method of the bot`() {
        val result = channel().send(message("V01 profit +0.1234 SOL", "V02 no trade"))

        assertEquals(DeliveryResult.Delivered, result)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/bot$TOKEN/sendMessage", request.path)
        assertContains(request.contentType, "application/json")
        val body = JsonMapper.builder().build().readTree(request.body)
        assertEquals(CHAT_ID, body.path("chat_id").asString())
        assertEquals("HTML", body.path("parse_mode").asString())
        assertTrue(body.path("link_preview_options").path("is_disabled").asBoolean())
        assertEquals(
            "<b>Buy 7Xyz</b>\nV01 profit +0.1234 SOL\nV02 no trade\n\n" +
                "<a href=\"http://localhost:8081/buys/1\">Open buy</a>\n<a href=\"http://localhost:8081/days/2026-11-22\">Day</a>",
            body.path("text").asString(),
        )
    }

    @Test
    fun `renders only the first link as a plain url in plain style`() {
        channel(LinkStyle.PLAIN).send(message("line"))

        val text = JsonMapper.builder().build().readTree(server.requests.single().body).path("text").asString()
        assertEquals("<b>Buy 7Xyz</b>\nline\n\nhttp://localhost:8081/buys/1", text)
        assertFalse(text.contains("<a "))
    }

    @Test
    fun `plain style with a message cut at 4096 characters keeps the first link before the note`() {
        val lines = (1..100).map { "line %03d ".format(it) + "x".repeat(90) }

        channel(LinkStyle.PLAIN).send(message(*lines.toTypedArray()))

        val text = JsonMapper.builder().build().readTree(server.requests.single().body).path("text").asString()
        assertTrue(text.length <= 4096, "length=${text.length}")
        assertTrue(text.endsWith("\n\nhttp://localhost:8081/buys/1\n… more in the console"), text.takeLast(80))
        assertFalse(text.contains("<a "))
        assertFalse(text.contains("/days/2026-11-22"), "plain style shows only the first link")
    }

    @Test
    fun `escapes html in the title, lines and links`() {
        val links = listOf(RenderedLink("A & <B>", "http://localhost:8081/x?a=1&b=\"2\""))
        channel().send(RenderedNotification("T <1> & 2", listOf("x < y && z > w"), links))

        val text = JsonMapper.builder().build().readTree(server.requests.single().body).path("text").asString()
        assertEquals(
            "<b>T &lt;1&gt; &amp; 2</b>\nx &lt; y &amp;&amp; z &gt; w\n\n" +
                "<a href=\"http://localhost:8081/x?a=1&amp;b=&quot;2&quot;\">A &amp; &lt;B&gt;</a>",
            text,
        )
    }

    @Test
    fun `cuts a message over 4096 characters at a line boundary and says more is in the console`() {
        val lines = (1..100).map { "line %03d ".format(it) + "x".repeat(90) }

        channel().send(message(*lines.toTypedArray()))

        val text = JsonMapper.builder().build().readTree(server.requests.single().body).path("text").asString()
        assertTrue(text.length <= 4096, "length=${text.length}")
        assertTrue(text.endsWith("\n… more in the console"))
        val kept = text.lines().filter { it.startsWith("line ") }
        assertEquals(lines.take(kept.size), kept)
        assertTrue(kept.size in 30..45)
        assertContains(text, "<a href=\"http://localhost:8081/buys/1\">Open buy</a>")
    }

    @Test
    fun `sends a message of exactly 4096 characters whole`() {
        val title = "Buy 7Xyz"
        val header = "<b>$title</b>\n"
        val footer = "\n\n<a href=\"http://localhost:8081/buys/1\">Open buy</a>"
        val body = "y".repeat(4096 - header.length - footer.length)

        channel().send(RenderedNotification(title, listOf(body), listOf(LINKS.first())))

        val text = JsonMapper.builder().build().readTree(server.requests.single().body).path("text").asString()
        assertEquals(4096, text.length)
        assertFalse(text.contains("more in the console"))
    }

    @Test
    fun `counts a delivered request`() {
        val answer = """{"ok":true,"result":{"message_id":9}}"""
        server.respond(200, answer)

        channel().send(message("a"))

        assertEquals(
            listOf(ApiCall(ApiProvider.TELEGRAM, "sendMessage", ApiUsageContext(ApiTask.NOTIFICATION, null), ApiCallOutcome.OK, answer.length.toLong())),
            calls,
        )
    }

    @Test
    fun `asks to retry after the wait Telegram states for a 429`() {
        server.respond(429, """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 17","parameters":{"retry_after":17}}""")

        val result = channel().send(message("a"))

        assertEquals(DeliveryResult.RetryLater(DeliveryCategory.RATE_LIMITED, Duration.ofSeconds(17)), result)
        assertEquals(ApiCallOutcome.RATE_LIMITED, calls.single().outcome)
    }

    @Test
    fun `asks to retry with no stated wait when a 429 has no retry_after`() {
        server.respond(429, "not json")

        assertEquals(DeliveryResult.RetryLater(DeliveryCategory.RATE_LIMITED, null), channel().send(message("a")))
    }

    @Test
    fun `the usage page knows the call, so sendMessage is not shown as unknown`() {
        assertEquals(ApiPurpose.SEND_NOTIFICATION, ApiPurpose.of(ApiProvider.TELEGRAM, "sendMessage"))
    }

    @Test
    fun `asks to retry on a server error`() {
        server.respond(500, """{"ok":false}""")

        val result = channel().send(message("a"))

        assertEquals(DeliveryResult.RetryLater(DeliveryCategory.UNAVAILABLE, null), result)
        assertEquals(ApiCallOutcome.FAILED, calls.single().outcome)
    }

    @Test
    fun `rejects a wrong token, a forbidden chat and a bad request`() {
        val answers = listOf(401 to DeliveryCategory.UNAUTHORIZED, 403 to DeliveryCategory.FORBIDDEN, 400 to DeliveryCategory.BAD_REQUEST, 404 to DeliveryCategory.REJECTED)

        answers.forEach { (status, category) ->
            server.respond(status, """{"ok":false,"description":"no"}""")
            assertEquals(DeliveryResult.Rejected(category), channel().send(message("a")), "status=$status")
        }
        assertTrue(calls.all { it.outcome == ApiCallOutcome.FAILED })
    }

    @Test
    fun `asks to retry on a read timeout`() {
        server.hang()

        val result = channel(readTimeout = Duration.ofMillis(300)).send(message("a"))

        assertEquals(DeliveryResult.RetryLater(DeliveryCategory.TIMEOUT, null), result)
        assertEquals(ApiCall(ApiProvider.TELEGRAM, "sendMessage", ApiUsageContext(ApiTask.NOTIFICATION, null), ApiCallOutcome.FAILED, 0), calls.single())
    }

    @Test
    fun `asks to retry when nothing answers`() {
        val deadChannel = channel(connectTimeout = Duration.ofSeconds(30))
        server.close()

        val result = deadChannel.send(message("a"))

        assertEquals(DeliveryResult.RetryLater(DeliveryCategory.UNAVAILABLE, null), result)
        assertEquals(ApiCallOutcome.FAILED, calls.single().outcome)
    }

    @Test
    fun `no log line holds the token whatever Telegram answers`() {
        listOf(200, 429, 500, 401).forEach { status ->
            server.respond(status, """{"ok":false}""")
            channel().send(message("a"))
        }
        server.hang()
        channel(readTimeout = Duration.ofMillis(200)).send(message("a"))
        val deadChannel = channel()
        server.close()
        deadChannel.send(message("a"))

        assertTrue(logs.events.isNotEmpty())
        assertFalse(logs.everything().contains(TOKEN))
    }

    private class FakeTelegramServer private constructor(private val server: HttpServer) : AutoCloseable {
        class Request(val method: String, val path: String, val contentType: String, val body: String)

        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        private val release = CountDownLatch(1)
        private val executor = Executors.newCachedThreadPool()
        @Volatile private var status = 200
        @Volatile private var body = """{"ok":true,"result":{"message_id":9}}"""
        @Volatile private var hang = false

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        fun respond(status: Int, body: String) {
            this.status = status
            this.body = body
            hang = false
        }

        fun hang() {
            hang = true
        }

        override fun close() {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }

        private fun start(): FakeTelegramServer {
            server.createContext("/") { exchange ->
                requests += Request(
                    exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.getFirst("Content-Type").orEmpty(),
                    String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8),
                )
                if (hang) release.await(1, TimeUnit.MINUTES)
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.executor = executor
            server.start()
            return this
        }

        companion object {
            fun start(): FakeTelegramServer =
                FakeTelegramServer(HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)).start()
        }
    }

    private companion object {
        /** The JDK test server logs the request line it receives; that is the fake's own diagnostic, not the code under test. */
        const val FAKE_SERVER_LOGGER = "com.sun.net.httpserver"
        const val TOKEN = "123456:FAKE-test-token-value"
        const val CHAT_ID = "-1001234567890"
        val LINKS = listOf(
            RenderedLink("Open buy", "http://localhost:8081/buys/1"),
            RenderedLink("Day", "http://localhost:8081/days/2026-11-22"),
        )
    }
}
