package com.trenching.bot.notification

import com.trenching.bot.notification.application.NotificationOutboxStore
import com.trenching.bot.notification.domain.DeliveryCategory
import com.trenching.bot.notification.domain.DeliveryDecision
import com.trenching.bot.notification.domain.NewNotification
import com.trenching.bot.notification.domain.NotificationId
import com.trenching.bot.notification.domain.NotificationState
import com.trenching.bot.notification.infrastructure.persistence.NotificationOutboxRepository
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * A context of its own (the extra property keeps it out of the shared cache) with notifications configured. The dispatcher's
 * first run is an hour away and its Telegram address closed, so nothing here can reach a provider.
 */
@SpringBootTest(
    properties = [
        "spring.application.name=notification-outbox",
        "trenching.notification.telegram.bot-token=123456:FAKE-test-token-value",
        "trenching.notification.telegram.chat-id=-1001234567890",
        "trenching.notification.telegram.api-base-url=http://127.0.0.1:1",
        "trenching.notification.dispatch-interval=1h",
        "trenching.notification.kinds=BUY_RESULT,DAY_VERDICT",
    ],
)
@Tag("integration")
@ActiveProfiles("test")
@Import(PostgresTestDatabaseConfiguration::class)
class NotificationOutboxTest {
    @Autowired private lateinit var publisher: NotificationPublisher
    @Autowired private lateinit var store: NotificationOutboxStore
    @Autowired private lateinit var repository: NotificationOutboxRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val transactions get() = TransactionTemplate(transactionManager)

    @BeforeEach
    fun emptyTable() = repository.deleteAll()

    @Test
    fun `publish stores one pending row keyed by the sha256 of the notification key`() {
        publisher.publish(buyResult("BUY_RESULT|item-1"))

        val row = repository.findAll().single()
        assertEquals(sha256Hex("BUY_RESULT|item-1"), row.id)
        assertEquals("BUY_RESULT|item-1", row.notificationKey)
        assertEquals(NotificationKind.BUY_RESULT, row.kind)
        assertEquals(NotificationState.PENDING, row.state)
        assertEquals(0, row.attempts)
        assertEquals(row.createdAt, row.nextAttemptAt)
        assertNull(row.lastError)
        assertNull(row.sentAt)
        assertEquals(
            """{"title":"Buy 7Xyz","lines":["V01 profit"],"links":[{"label":"Open buy","path":"/buys/item-1"}]}""",
            row.payload,
        )
    }

    @Test
    fun `a repeated publish stores nothing and raises nothing`() {
        publisher.publish(buyResult("BUY_RESULT|item-1", title = "first"))
        publisher.publish(buyResult("BUY_RESULT|item-1", title = "second"))

        val row = repository.findAll().single()
        assertTrue(row.payload.contains("first"))
    }

    @Test
    fun `a repeated publish inside the callers transaction does not mark it rollback only`() {
        publisher.publish(buyResult("BUY_RESULT|item-1"))

        transactions.execute { status ->
            publisher.publish(buyResult("BUY_RESULT|item-1"))
            assertFalse(status.isRollbackOnly)
            publisher.publish(buyResult("BUY_RESULT|item-2"))
            assertFalse(status.isRollbackOnly)
        }

        assertEquals(setOf("BUY_RESULT|item-1", "BUY_RESULT|item-2"), repository.findAll().map { it.notificationKey }.toSet())
    }

    @Test
    fun `a publish inside a transaction that rolls back leaves no row`() {
        transactions.execute { status ->
            publisher.publish(buyResult("BUY_RESULT|item-1"))
            status.setRollbackOnly()
        }

        assertEquals(0, repository.count())
    }

    @Test
    fun `a publish inside a transaction that fails leaves no row`() {
        assertFailsWith<IllegalStateException> {
            transactions.execute {
                publisher.publish(buyResult("BUY_RESULT|item-1"))
                error("the caller failed after publishing")
            }
        }

        assertEquals(0, repository.count())
    }

    @Test
    fun `a kind that is not enabled is not stored`() {
        publisher.publish(buyResult("RPC_BALANCE_LOW|primary|2026-12-01", kind = NotificationKind.RPC_BALANCE_LOW))
        publisher.publish(buyResult("DAY_VERDICT|plan|2026-11-20", kind = NotificationKind.DAY_VERDICT))

        assertEquals(listOf("DAY_VERDICT|plan|2026-11-20"), repository.findAll().map { it.notificationKey })
    }

    @Test
    fun `a publish that meets another open transaction on the same key waits, then skips without raising or marking rollback only`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val second = transactions.execute {
                publisher.publish(buyResult("BUY_RESULT|item-1"))
                val other = pool.submit<Boolean> {
                    transactions.execute { status ->
                        publisher.publish(buyResult("BUY_RESULT|item-1", title = "second"))
                        status.isRollbackOnly
                    }
                }
                assertTrue(waitUntilBlockedOnInsert(), "the second transaction should be waiting on the first one's row")
                other
            }

            assertFalse(checkNotNull(second).get(30, TimeUnit.SECONDS), "the second transaction is not rollback only")
        } finally {
            pool.shutdownNow()
        }

        val row = repository.findAll().single()
        assertTrue(row.payload.contains("Buy 7Xyz"), "the first publisher's row stays")
    }

    @Test
    fun `many overlapping transactions publishing one key store one row`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map {
                pool.submit<Boolean> {
                    transactions.execute { status ->
                        publisher.publish(buyResult("BUY_RESULT|item-1"))
                        status.isRollbackOnly
                    }
                }
            }
            futures.forEach { assertFalse(checkNotNull(it.get(60, TimeUnit.SECONDS))) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, repository.count())
    }

    private fun waitUntilBlockedOnInsert(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val waiting = jdbc.queryForObject(
                "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and wait_event = 'transactionid'",
                Int::class.java,
            ) ?: 0
            if (waiting > 0) return true
            Thread.sleep(25)
        }
        return false
    }

    @Test
    fun `the schema holds the unique key and the due index`() {
        val indexes = jdbc.queryForList("select indexname from pg_indexes where tablename = 'notification_outbox'", String::class.java)
        assertTrue("uq_notification_outbox_key" in indexes, "indexes=$indexes")
        assertTrue("idx_notification_outbox_due" in indexes, "indexes=$indexes")

        publisher.publish(buyResult("BUY_RESULT|item-1"))
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "insert into notification_outbox (id, notification_key, kind, payload, state, attempts, next_attempt_at, created_at) " +
                    "values ('another-id', 'BUY_RESULT|item-1', 'BUY_RESULT', '{}', 'PENDING', 0, now(), now())",
            )
        }
    }

    @Test
    fun `due returns pending rows that are due, oldest first, up to the limit`() {
        store.insertIfAbsent(row("C", NOW.minusSeconds(10)))
        store.insertIfAbsent(row("A", NOW.minusSeconds(30)))
        store.insertIfAbsent(row("B", NOW.minusSeconds(20)))
        store.insertIfAbsent(row("FUTURE", NOW.minusSeconds(40)))
        store.record(NotificationId.of("FUTURE"), 1, DeliveryDecision.RetryAt(NOW.plusSeconds(60), DeliveryCategory.UNAVAILABLE), NOW)

        assertEquals(listOf("A", "B", "C"), store.due(NOW, 20).map { it.key })
        assertEquals(listOf("A", "B"), store.due(NOW, 2).map { it.key })
        assertEquals(listOf("FUTURE", "A", "B", "C").sorted(), store.due(NOW.plusSeconds(60), 20).map { it.key }.sorted())
    }

    @Test
    fun `record stores the decision and a decided row is no longer due`() {
        store.insertIfAbsent(row("SENT", NOW.minusSeconds(30)))
        store.insertIfAbsent(row("DEAD", NOW.minusSeconds(20)))
        store.insertIfAbsent(row("LATER", NOW.minusSeconds(10)))

        store.record(NotificationId.of("SENT"), 1, DeliveryDecision.Sent, NOW)
        store.record(NotificationId.of("DEAD"), 2, DeliveryDecision.Abandon(DeliveryCategory.UNAUTHORIZED), NOW)
        store.record(NotificationId.of("LATER"), 3, DeliveryDecision.RetryAt(NOW.plusSeconds(90), DeliveryCategory.RATE_LIMITED), NOW)

        val sent = repository.findById(NotificationId.of("SENT")).orElseThrow()
        assertEquals(listOf(NotificationState.SENT, 1, NOW), listOf(sent.state, sent.attempts, sent.sentAt))
        val dead = repository.findById(NotificationId.of("DEAD")).orElseThrow()
        assertEquals(listOf(NotificationState.FAILED, 2, DeliveryCategory.UNAUTHORIZED), listOf(dead.state, dead.attempts, dead.lastError))
        val later = repository.findById(NotificationId.of("LATER")).orElseThrow()
        assertEquals(listOf(NotificationState.PENDING, 3, NOW.plusSeconds(90)), listOf(later.state, later.attempts, later.nextAttemptAt))
        assertEquals(DeliveryCategory.RATE_LIMITED, later.lastError)
        assertEquals(emptyList(), store.due(NOW, 20))
    }

    @Test
    fun `record of a row that was deleted does nothing`() {
        store.record(NotificationId.of("GONE"), 1, DeliveryDecision.Sent, NOW)

        assertEquals(0, repository.count())
    }

    @Test
    fun `deleteCreatedBefore never removes a pending row`() {
        store.insertIfAbsent(row("SENT", NOW.minusSeconds(1000)))
        store.insertIfAbsent(row("FAILED", NOW.minusSeconds(1000)))
        store.insertIfAbsent(row("PENDING", NOW.minusSeconds(1000)))
        store.record(NotificationId.of("SENT"), 1, DeliveryDecision.Sent, NOW)
        store.record(NotificationId.of("FAILED"), 1, DeliveryDecision.Abandon(DeliveryCategory.FORBIDDEN), NOW)

        assertEquals(2, store.deleteCreatedBefore(NOW, 10))

        assertEquals(listOf("PENDING"), repository.findAll().map { it.notificationKey })
    }

    @Test
    fun `claim counts the attempt once and holds the row back, a second claim from the same read fails`() {
        store.insertIfAbsent(row("A", NOW.minusSeconds(30)))

        assertTrue(store.claim(NotificationId.of("A"), 0, NOW.plusSeconds(30)))
        assertFalse(store.claim(NotificationId.of("A"), 0, NOW.plusSeconds(30)), "the row was claimed since it was read")

        val claimed = repository.findById(NotificationId.of("A")).orElseThrow()
        assertEquals(listOf(NotificationState.PENDING, 1, NOW.plusSeconds(30)), listOf(claimed.state, claimed.attempts, claimed.nextAttemptAt))
        assertEquals(emptyList(), store.due(NOW, 20))
        assertEquals(listOf("A"), store.due(NOW.plusSeconds(30), 20).map { it.key })
    }

    @Test
    fun `claim of a decided or deleted row does nothing`() {
        store.insertIfAbsent(row("SENT", NOW.minusSeconds(30)))
        store.record(NotificationId.of("SENT"), 1, DeliveryDecision.Sent, NOW)

        assertFalse(store.claim(NotificationId.of("SENT"), 1, NOW.plusSeconds(30)))
        assertFalse(store.claim(NotificationId.of("GONE"), 0, NOW.plusSeconds(30)))
    }

    @Test
    fun `deleteCreatedBefore removes at most the limit of older rows`() {
        repeat(5) {
            store.insertIfAbsent(row("old$it", NOW.minusSeconds(1000L - it)))
            store.record(NotificationId.of("old$it"), 1, DeliveryDecision.Sent, NOW)
        }
        store.insertIfAbsent(row("new", NOW))

        assertEquals(3, store.deleteCreatedBefore(NOW.minusSeconds(100), 3))
        assertEquals(2, store.deleteCreatedBefore(NOW.minusSeconds(100), 3))
        assertEquals(0, store.deleteCreatedBefore(NOW.minusSeconds(100), 3))
        assertNotNull(repository.findById(NotificationId.of("new")).orElse(null))
    }

    private fun buyResult(key: String, kind: NotificationKind = NotificationKind.BUY_RESULT, title: String = "Buy 7Xyz") =
        OutboundNotification(key, kind, title, listOf("V01 profit"), listOf(NotificationLink("Open buy", "/buys/item-1")))

    private fun row(key: String, createdAt: Instant) = NewNotification(NotificationId.of(key), key, NotificationKind.BUY_RESULT, "{}", createdAt)

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-22T10:00:00Z")
    }
}
