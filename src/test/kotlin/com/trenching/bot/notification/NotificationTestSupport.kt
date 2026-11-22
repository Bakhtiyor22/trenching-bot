package com.trenching.bot.notification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trenching.bot.notification.application.NotificationChannel
import com.trenching.bot.notification.application.NotificationOutboxStore
import com.trenching.bot.notification.domain.DeliveryCategory
import com.trenching.bot.notification.domain.DeliveryDecision
import com.trenching.bot.notification.domain.DeliveryResult
import com.trenching.bot.notification.domain.NewNotification
import com.trenching.bot.notification.domain.NotificationId
import com.trenching.bot.notification.domain.NotificationState
import com.trenching.bot.notification.domain.PendingNotification
import com.trenching.bot.notification.domain.RenderedNotification
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

/** A publisher for other modules' tests: keeps what was published, in order, and stores nothing. */
class RecordingNotificationPublisher : NotificationPublisher {
    private val published = mutableListOf<OutboundNotification>()

    val notifications: List<OutboundNotification> get() = synchronized(published) { published.toList() }

    override fun publish(notification: OutboundNotification) {
        synchronized(published) { published += notification }
    }
}

class NotificationClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

/** The outbox in memory, with the same ordering, uniqueness and due rules as the database. */
class InMemoryNotificationOutboxStore : NotificationOutboxStore {
    class Row(
        val notification: NewNotification,
        var state: NotificationState = NotificationState.PENDING,
        var attempts: Int = 0,
        var nextAttemptAt: Instant = notification.createdAt,
        var lastError: DeliveryCategory? = null,
        var sentAt: Instant? = null,
    )

    val rows = linkedMapOf<String, Row>()
    val deleteCalls = mutableListOf<Pair<Instant, Int>>()

    /** Set to make the next call of that kind raise, as a database that fails would. */
    var failClaims = false
    var failRecords = false

    fun row(key: String): Row = rows.getValue(NotificationId.of(key))

    fun add(key: String, createdAt: Instant, payload: String = PAYLOAD, kind: NotificationKind = NotificationKind.BUY_RESULT) {
        insertIfAbsent(NewNotification(NotificationId.of(key), key, kind, payload, createdAt))
    }

    override fun insertIfAbsent(notification: NewNotification): Boolean {
        if (notification.id in rows) return false
        rows[notification.id] = Row(notification)
        return true
    }

    override fun due(now: Instant, limit: Int): List<PendingNotification> =
        rows.values.filter { it.state == NotificationState.PENDING && it.nextAttemptAt <= now }
            .sortedBy { it.notification.createdAt }.take(limit)
            .map { PendingNotification(it.notification.id, it.notification.key, it.notification.kind, it.notification.payload, it.attempts) }

    override fun claim(id: String, expectedAttempts: Int, nextAttemptAt: Instant): Boolean {
        check(!failClaims) { "the database is down" }
        val row = rows[id]?.takeIf { it.state == NotificationState.PENDING && it.attempts == expectedAttempts } ?: return false
        row.attempts = expectedAttempts + 1
        row.nextAttemptAt = nextAttemptAt
        return true
    }

    override fun record(id: String, attempts: Int, decision: DeliveryDecision, at: Instant) {
        check(!failRecords) { "the database is down" }
        val row = rows.getValue(id)
        row.attempts = attempts
        when (decision) {
            DeliveryDecision.Sent -> { row.state = NotificationState.SENT; row.sentAt = at; row.lastError = null }
            is DeliveryDecision.RetryAt -> { row.nextAttemptAt = decision.at; row.lastError = decision.category }
            is DeliveryDecision.Abandon -> { row.state = NotificationState.FAILED; row.lastError = decision.category }
        }
    }

    override fun deleteCreatedBefore(cutoff: Instant, limit: Int): Int {
        deleteCalls += cutoff to limit
        val doomed = rows.values.filter { it.state != NotificationState.PENDING && it.notification.createdAt < cutoff }.take(limit).map { it.notification.id }
        doomed.forEach(rows::remove)
        return doomed.size
    }

    companion object {
        const val PAYLOAD = """{"title":"Buy","lines":["a"],"links":[{"label":"Open","path":"/buys/1"}]}"""
    }
}

/** Answers with the scripted results in turn, then with the last one forever; remembers what it was asked to send. */
class ScriptedNotificationChannel(private vararg val results: DeliveryResult) : NotificationChannel {
    val sent = mutableListOf<RenderedNotification>()
    var failure: RuntimeException? = null
    var onSend: () -> Unit = {}

    override fun send(notification: RenderedNotification): DeliveryResult {
        sent += notification
        onSend()
        failure?.let { throw it }
        return results[(sent.size - 1).coerceAtMost(results.size - 1)]
    }
}

/** Captures what the named logger's classes log while a test runs: `com.trenching.bot` by default, or the root logger for everything. */
class LogCapture(loggerName: String = "com.trenching.bot", private val ignoring: List<String> = emptyList()) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(loggerName) as Logger
    private val appender = ListAppender<ILoggingEvent>().also { it.start() }
    private val previousLevel = logger.level

    init {
        logger.level = Level.DEBUG
        logger.addAppender(appender)
    }

    val events: List<ILoggingEvent> get() = appender.list.filter { event -> ignoring.none { event.loggerName.startsWith(it) } }

    fun at(level: Level): List<String> = events.filter { it.level == level }.map { it.formattedMessage }

    /** Everything a line could carry: message, arguments, and the text of any throwable. */
    fun everything(): String = events.joinToString("\n") { event ->
        val thrown = event.throwableProxy?.let { proxy -> generateSequence(proxy) { it.cause }.joinToString { it.message.orEmpty() + it.className } }
        listOfNotNull(event.formattedMessage, event.message, thrown).joinToString(" ")
    }

    override fun close() {
        logger.detachAppender(appender)
        logger.level = previousLevel
    }
}
