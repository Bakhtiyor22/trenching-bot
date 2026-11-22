package com.trenching.bot.notification

import ch.qos.logback.classic.Level
import com.trenching.bot.notification.application.DispatchPause
import com.trenching.bot.notification.application.NotificationDispatcher
import com.trenching.bot.notification.application.NotificationRenderer
import com.trenching.bot.notification.domain.DeliveryCategory
import com.trenching.bot.notification.domain.DeliveryResult
import com.trenching.bot.notification.domain.NotificationRetryPolicy
import com.trenching.bot.notification.domain.NotificationState
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationDispatcherTest {
    private val clock = NotificationClock(NOW)
    private val store = InMemoryNotificationOutboxStore()
    private val logs = LogCapture()
    private var pauses = 0
    private var pauseContinues = true

    @AfterTest
    fun detachLog() = logs.close()

    private fun dispatcher(channel: ScriptedNotificationChannel, maxAttempts: Int = 54, retention: Duration = Duration.ofDays(30)) =
        NotificationDispatcher(
            store, channel, NotificationRenderer("http://localhost:8081/"), NotificationRetryPolicy(maxAttempts), retention, clock,
            DispatchPause { pauses++; pauseContinues },
        )

    @Test
    fun `sends pending rows oldest first and marks each sent`() {
        store.add("B", NOW.minusSeconds(10))
        store.add("A", NOW.minusSeconds(20))
        val channel = ScriptedNotificationChannel(DeliveryResult.Delivered)

        dispatcher(channel).dispatch()

        assertEquals(2, channel.sent.size)
        assertEquals(NotificationState.SENT, store.row("A").state)
        assertEquals(NotificationState.SENT, store.row("B").state)
        assertEquals(NOW, store.row("A").sentAt)
        assertEquals(1, store.row("A").attempts)
    }

    @Test
    fun `renders the stored payload with absolute console links`() {
        store.add("A", NOW.minusSeconds(1))
        val channel = ScriptedNotificationChannel(DeliveryResult.Delivered)

        dispatcher(channel).dispatch()

        val sent = channel.sent.single()
        assertEquals("Buy", sent.title)
        assertEquals(listOf("a"), sent.lines)
        assertEquals("http://localhost:8081/buys/1", sent.links.single().url)
        assertEquals("Open", sent.links.single().label)
    }

    @Test
    fun `sends at most 20 rows a run with a pause between sends and leaves the rest pending`() {
        repeat(25) { store.add("K$it", NOW.minusSeconds(100L - it)) }
        val channel = ScriptedNotificationChannel(DeliveryResult.Delivered)

        dispatcher(channel).dispatch()

        assertEquals(20, channel.sent.size)
        assertEquals(19, pauses)
        assertEquals(5, store.rows.values.count { it.state == NotificationState.PENDING })
        assertEquals(NotificationState.PENDING, store.row("K20").state)
    }

    @Test
    fun `stops the run at the first retry later so an outage does not hold the thread`() {
        store.add("A", NOW.minusSeconds(30))
        store.add("B", NOW.minusSeconds(20))
        store.add("C", NOW.minusSeconds(10))
        val channel = ScriptedNotificationChannel(DeliveryResult.RetryLater(DeliveryCategory.UNAVAILABLE, null))

        dispatcher(channel).dispatch()

        assertEquals(1, channel.sent.size)
        assertEquals(NotificationState.PENDING, store.row("A").state)
        assertEquals(1, store.row("A").attempts)
        assertEquals(DeliveryCategory.UNAVAILABLE, store.row("A").lastError)
        assertEquals(0, store.row("B").attempts)
        assertEquals(0, pauses)
    }

    @Test
    fun `retries after the wait the provider stated`() {
        store.add("A", NOW.minusSeconds(30))
        val channel = ScriptedNotificationChannel(DeliveryResult.RetryLater(DeliveryCategory.RATE_LIMITED, Duration.ofSeconds(47)))

        dispatcher(channel).dispatch()

        assertEquals(NOW.plusSeconds(47), store.row("A").nextAttemptAt)
        assertEquals(DeliveryCategory.RATE_LIMITED, store.row("A").lastError)
    }

    @Test
    fun `retries a row with a doubling backoff from 30 seconds`() {
        store.add("A", NOW.minusSeconds(30))
        val channel = ScriptedNotificationChannel(DeliveryResult.RetryLater(DeliveryCategory.UNAVAILABLE, null))
        val dispatcher = dispatcher(channel)

        val waits = (1..3).map {
            dispatcher.dispatch()
            Duration.between(clock.now, store.row("A").nextAttemptAt).also { clock.now = store.row("A").nextAttemptAt }
        }

        assertEquals(listOf(30L, 60L, 120L), waits.map { it.seconds })
    }

    @Test
    fun `does not send a row before its next attempt time`() {
        store.add("A", NOW.minusSeconds(30))
        val channel = ScriptedNotificationChannel(DeliveryResult.RetryLater(DeliveryCategory.UNAVAILABLE, null))
        val dispatcher = dispatcher(channel)
        dispatcher.dispatch()

        clock.now = NOW.plusSeconds(29)
        dispatcher.dispatch()
        assertEquals(1, channel.sent.size)

        clock.now = NOW.plusSeconds(30)
        dispatcher.dispatch()
        assertEquals(2, channel.sent.size)
    }

    @Test
    fun `fails the row with one warning after the last allowed attempt`() {
        store.add("A", NOW.minusSeconds(30))
        val channel = ScriptedNotificationChannel(DeliveryResult.RetryLater(DeliveryCategory.UNAVAILABLE, Duration.ofSeconds(1)))
        val dispatcher = dispatcher(channel, maxAttempts = 3)

        repeat(3) {
            dispatcher.dispatch()
            clock.now = clock.now.plusSeconds(5)
        }
        dispatcher.dispatch()

        assertEquals(3, channel.sent.size)
        assertEquals(NotificationState.FAILED, store.row("A").state)
        assertEquals(3, store.row("A").attempts)
        assertEquals(DeliveryCategory.UNAVAILABLE, store.row("A").lastError)
        val warnings = logs.at(Level.WARN)
        assertEquals(1, warnings.size)
        assertContains(warnings.single(), "attempts=3/3")
        assertContains(warnings.single(), "code=UNAVAILABLE")
    }

    @Test
    fun `fails a rejected row at once with one warning and goes on with the next`() {
        store.add("A", NOW.minusSeconds(30))
        store.add("B", NOW.minusSeconds(20))
        val channel = ScriptedNotificationChannel(DeliveryResult.Rejected(DeliveryCategory.UNAUTHORIZED), DeliveryResult.Delivered)

        dispatcher(channel).dispatch()

        assertEquals(NotificationState.FAILED, store.row("A").state)
        assertEquals(DeliveryCategory.UNAUTHORIZED, store.row("A").lastError)
        assertEquals(1, store.row("A").attempts)
        assertEquals(NotificationState.SENT, store.row("B").state)
        assertEquals(1, logs.at(Level.WARN).size)
        assertContains(logs.at(Level.WARN).single(), "code=UNAUTHORIZED")
        assertContains(logs.at(Level.WARN).single(), "kind=BUY_RESULT")
    }

    @Test
    fun `fails a row whose stored payload cannot be read without calling the channel`() {
        store.add("A", NOW.minusSeconds(30), payload = "not json")
        val channel = ScriptedNotificationChannel(DeliveryResult.Delivered)

        dispatcher(channel).dispatch()

        assertTrue(channel.sent.isEmpty())
        assertEquals(NotificationState.FAILED, store.row("A").state)
        assertEquals(DeliveryCategory.MALFORMED_PAYLOAD, store.row("A").lastError)
        assertEquals(1, logs.at(Level.WARN).size)
    }

    @Test
    fun `treats an exception from the channel as a retry and logs its class but never its message`() {
        store.add("A", NOW.minusSeconds(30))
        val channel = ScriptedNotificationChannel(DeliveryResult.Delivered)
        channel.failure = IllegalStateException("https://api.telegram.org/bot123:SECRET/sendMessage")

        dispatcher(channel).dispatch()

        assertEquals(NotificationState.PENDING, store.row("A").state)
        assertEquals(DeliveryCategory.UNEXPECTED, store.row("A").lastError)
        assertFalse(logs.everything().contains("SECRET"))
        assertContains(logs.everything(), "IllegalStateException")
    }

    @Test
    fun `stops the run when the pause is interrupted`() {
        store.add("A", NOW.minusSeconds(30))
        store.add("B", NOW.minusSeconds(20))
        pauseContinues = false
        val channel = ScriptedNotificationChannel(DeliveryResult.Delivered)

        dispatcher(channel).dispatch()

        assertEquals(1, channel.sent.size)
        assertEquals(NotificationState.PENDING, store.row("B").state)
    }

    @Test
    fun `deletes rows older than the retention and keeps newer ones`() {
        store.add("old", NOW.minus(Duration.ofDays(31)))
        store.add("edge", NOW.minus(Duration.ofDays(30)).plusSeconds(1))
        store.rows.values.forEach { it.state = NotificationState.SENT }
        val channel = ScriptedNotificationChannel(DeliveryResult.RetryLater(DeliveryCategory.UNAVAILABLE, null))

        dispatcher(channel).dispatch()

        assertTrue(NotificationIdOf("old") !in store.rows)
        assertTrue(NotificationIdOf("edge") in store.rows)
    }

    @Test
    fun `deletes expired rows in bounded batches`() {
        repeat(1_200) { store.add("old$it", NOW.minus(Duration.ofDays(40)).plusSeconds(it.toLong())) }
        store.rows.values.forEach { it.state = NotificationState.SENT }
        val channel = ScriptedNotificationChannel(DeliveryResult.Delivered)

        dispatcher(channel).dispatch()

        assertTrue(store.rows.isEmpty())
        assertEquals(listOf(500, 500, 500), store.deleteCalls.map { it.second })
        assertEquals(NOW.minus(Duration.ofDays(30)), store.deleteCalls.first().first)
    }

    @Test
    fun `purges at most once an hour`() {
        val channel = ScriptedNotificationChannel(DeliveryResult.Delivered)
        val dispatcher = dispatcher(channel)

        dispatcher.dispatch()
        clock.now = NOW.plusSeconds(30)
        dispatcher.dispatch()
        assertEquals(1, store.deleteCalls.size)

        clock.now = NOW.plus(Duration.ofHours(1))
        dispatcher.dispatch()
        assertEquals(2, store.deleteCalls.size)
    }

    @Test
    fun `a claim that fails sends nothing and stops the run`() {
        store.add("A", NOW.minusSeconds(30))
        store.add("B", NOW.minusSeconds(20))
        store.failClaims = true
        val channel = ScriptedNotificationChannel(DeliveryResult.Delivered)

        dispatcher(channel).dispatch()

        assertTrue(channel.sent.isEmpty())
        assertEquals(0, store.row("A").attempts)
        assertEquals(1, logs.at(Level.ERROR).size)
    }

    @Test
    fun `claims a row before sending it, with the attempt counted and the backoff time stored`() {
        store.add("A", NOW.minusSeconds(30))
        var seenAtSend: Pair<Int, Instant>? = null
        val channel = ScriptedNotificationChannel(DeliveryResult.Delivered)
        channel.onSend = { seenAtSend = store.row("A").let { it.attempts to it.nextAttemptAt } }

        dispatcher(channel).dispatch()

        assertEquals(1 to NOW.plusSeconds(30), seenAtSend)
    }

    @Test
    fun `an outcome that cannot be stored repeats the message with backoff and stops at the maximum attempts`() {
        store.add("A", NOW.minusSeconds(30))
        store.failRecords = true
        val channel = ScriptedNotificationChannel(DeliveryResult.Delivered)
        val dispatcher = dispatcher(channel, maxAttempts = 3)

        dispatcher.dispatch()
        clock.now = NOW.plusSeconds(29)
        dispatcher.dispatch()
        assertEquals(1, channel.sent.size)

        clock.now = NOW.plusSeconds(30)
        dispatcher.dispatch()
        clock.now = NOW.plusSeconds(90)
        dispatcher.dispatch()
        assertEquals(3, channel.sent.size)

        clock.now = NOW.plus(Duration.ofHours(1))
        store.failRecords = false
        dispatcher.dispatch()

        assertEquals(3, channel.sent.size)
        assertEquals(NotificationState.FAILED, store.row("A").state)
        assertEquals(3, store.row("A").attempts)
        assertEquals(1, logs.at(Level.WARN).size)
        assertFalse(logs.everything().contains("the database is down"), "the exception's text is not logged")
    }

    @Test
    fun `sends nothing to the provider until the wait a 429 asked for has passed`() {
        store.add("A", NOW.minusSeconds(30))
        store.add("B", NOW.minusSeconds(20))
        val channel = ScriptedNotificationChannel(
            DeliveryResult.RetryLater(DeliveryCategory.RATE_LIMITED, Duration.ofSeconds(100)), DeliveryResult.Delivered,
        )
        val dispatcher = dispatcher(channel)
        dispatcher.dispatch()
        assertEquals(1, channel.sent.size)

        clock.now = NOW.plusSeconds(60)
        dispatcher.dispatch()
        assertEquals(1, channel.sent.size, "B is due, but the provider asked to wait")
        assertEquals(0, store.row("B").attempts)

        clock.now = NOW.plusSeconds(100)
        dispatcher.dispatch()
        assertEquals(3, channel.sent.size)
        assertEquals(NotificationState.SENT, store.row("A").state)
        assertEquals(NotificationState.SENT, store.row("B").state)
    }

    @Test
    fun `a 429 without a stated wait pauses sending for the backoff of the row`() {
        store.add("A", NOW.minusSeconds(30))
        store.add("B", NOW.minusSeconds(20))
        val channel = ScriptedNotificationChannel(DeliveryResult.RetryLater(DeliveryCategory.RATE_LIMITED, null), DeliveryResult.Delivered)
        val dispatcher = dispatcher(channel)
        dispatcher.dispatch()

        clock.now = NOW.plusSeconds(29)
        dispatcher.dispatch()
        assertEquals(1, channel.sent.size)

        clock.now = NOW.plusSeconds(30)
        dispatcher.dispatch()
        assertEquals(3, channel.sent.size)
    }

    @Test
    fun `an outage that is not a 429 does not pause the next run`() {
        store.add("A", NOW.minusSeconds(30))
        store.add("B", NOW.minusSeconds(20))
        val channel = ScriptedNotificationChannel(DeliveryResult.RetryLater(DeliveryCategory.UNAVAILABLE, null), DeliveryResult.Delivered)
        val dispatcher = dispatcher(channel)
        dispatcher.dispatch()

        clock.now = NOW.plusSeconds(1)
        dispatcher.dispatch()

        assertEquals(NotificationState.SENT, store.row("B").state)
    }

    @Test
    fun `acceptance 5 - an error answer leaves the row pending with attempts rising, and a later 200 sends it`() {
        store.add("A", NOW.minusSeconds(30))
        val channel = ScriptedNotificationChannel(
            DeliveryResult.RetryLater(DeliveryCategory.UNAVAILABLE, null), DeliveryResult.RetryLater(DeliveryCategory.UNAVAILABLE, null),
            DeliveryResult.Delivered,
        )
        val dispatcher = dispatcher(channel)

        dispatcher.dispatch()
        assertEquals(listOf(NotificationState.PENDING, 1), store.row("A").let { listOf(it.state, it.attempts) })
        clock.now = NOW.plusSeconds(30)
        dispatcher.dispatch()
        assertEquals(listOf(NotificationState.PENDING, 2), store.row("A").let { listOf(it.state, it.attempts) })
        clock.now = NOW.plusSeconds(90)
        dispatcher.dispatch()

        assertEquals(listOf(NotificationState.SENT, 3), store.row("A").let { listOf(it.state, it.attempts) })
        assertEquals(clock.now, store.row("A").sentAt)
    }

    @Test
    fun `retention deletes only sent and failed rows and keeps a pending one however old`() {
        store.add("sent", NOW.minus(Duration.ofDays(40)))
        store.add("failed", NOW.minus(Duration.ofDays(40)))
        store.add("pending", NOW.minus(Duration.ofDays(40)))
        store.row("sent").state = NotificationState.SENT
        store.row("failed").state = NotificationState.FAILED
        store.row("pending").nextAttemptAt = NOW.plusSeconds(60)

        dispatcher(ScriptedNotificationChannel(DeliveryResult.Delivered)).dispatch()

        assertEquals(listOf(NotificationIdOf("pending")), store.rows.keys.toList())
    }

    private fun NotificationIdOf(key: String) = com.trenching.bot.notification.domain.NotificationId.of(key)

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-22T10:00:00Z")
    }
}
