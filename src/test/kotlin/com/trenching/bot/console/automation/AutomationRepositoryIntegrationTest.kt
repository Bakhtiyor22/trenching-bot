package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationFixtures.WALLET
import com.trenching.bot.console.automation.AutomationRows.item
import com.trenching.bot.console.automation.AutomationRows.session
import com.trenching.bot.console.automation.AutomationRows.sweepRun
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.BackfillState
import com.trenching.bot.console.automation.domain.DiscoveryCursor
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkEntity
import com.trenching.bot.console.automation.infrastructure.persistence.DiscoveryCursorColumns
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class AutomationRepositoryIntegrationTest : AutomationDatabaseTest() {
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `a second session starting with the same item is refused`() {
        sessions.saveAndFlush(session("s1", AutomationSessionState.OPEN, EVENING, firstItemId = "item-1"))

        assertFailsWith<DataIntegrityViolationException> {
            sessions.saveAndFlush(session("s2", AutomationSessionState.OPEN, EVENING, firstItemId = "item-1"))
        }
    }

    @Test
    fun `a second item for the same buy is refused`() {
        items.saveAndFlush(item(1, "s1", mint = 1, signature = 1))

        assertFailsWith<DataIntegrityViolationException> { items.saveAndFlush(item(2, "s1", id = "other", mint = 2, signature = 1)) }
    }

    @Test
    fun `a second item for the same mint is refused`() {
        items.saveAndFlush(item(1, "s1", mint = 1, signature = 1))

        assertFailsWith<DataIntegrityViolationException> { items.saveAndFlush(item(2, "s1", mint = 1, signature = 2)) }
    }

    @Test
    fun `a second watermark row for the wallet is refused`() {
        watermarks.saveAndFlush(AutomationWatermarkEntity(WALLET, 1, "sig-1", 2, -1, EVENING))

        assertFailsWith<DataIntegrityViolationException> { watermarks.saveAndFlush(AutomationWatermarkEntity(WALLET, 2, "sig-2", 2, -1, EVENING)) }
    }

    @Test
    fun `a second sweep run for the same item, plan and label is refused`() {
        sweepRuns.saveAndFlush(sweepRun("run-a", "item-1", "V00"))

        assertFailsWith<DataIntegrityViolationException> { sweepRuns.saveAndFlush(sweepRun("run-b", "item-1", "V00")) }
    }

    @Test
    fun `due items are the pending ones whose next action has come, those of open or closing sessions before the phase 0 backlog of closed ones, oldest buy first, one page at a time`() {
        sessions.saveAll(listOf(
            session("s1", AutomationSessionState.OPEN, EVENING),
            session("s2", AutomationSessionState.CLOSING, EVENING.minusSeconds(7200)),
            session("s3", AutomationSessionState.CLOSED, EVENING.minusSeconds(14400)),
        ))
        items.saveAll(listOf(
            item(1, "s1", blockTime = EVENING.plusSeconds(30), nextActionAt = EVENING),
            item(2, "s1", blockTime = EVENING.plusSeconds(10), nextActionAt = EVENING.minusSeconds(60)),
            item(3, "s1", blockTime = EVENING.plusSeconds(20), nextActionAt = EVENING.plusSeconds(1)),
            item(4, "s1", AutomationItemState.FAILED, blockTime = EVENING, nextActionAt = EVENING),
            item(5, "s2", AutomationItemState.WAITING, blockTime = EVENING.plusSeconds(40), nextActionAt = EVENING),
            item(6, "s1", AutomationItemState.BLOCKED_BUDGET, blockTime = EVENING.plusSeconds(50), nextActionAt = null),
            item(7, "s3", blockTime = EVENING, nextActionAt = EVENING),
            item(8, "s3", AutomationItemState.WAITING, blockTime = EVENING.minusSeconds(5), nextActionAt = EVENING),
        ))
        val pending = AutomationItemState.entries.filterNot { it.terminal }

        val first = items.findDue(pending, BackfillState.CAPTURING_STATES, EVENING, PageRequest.of(0, 2)).map { it.id }
        val second = items.findDue(pending, BackfillState.CAPTURING_STATES, EVENING, PageRequest.of(1, 2)).map { it.id }

        val third = items.findDue(pending, BackfillState.CAPTURING_STATES, EVENING, PageRequest.of(2, 2)).map { it.id }

        assertEquals(listOf("item-2", "item-1"), first)
        assertEquals(listOf("item-5", "item-6"), second)
        assertEquals(listOf("item-8", "item-7"), third, "the phase 0 backlog of a closed session comes after every item of an open or closing one")
    }

    @Test
    fun `requests are summed per session over the items that have any`() {
        items.saveAll(listOf(
            item(1, "s1").apply { requestsUsed = 120 }, item(2, "s1").apply { requestsUsed = 80 }, item(3, "s1"), item(4, "s2"),
        ))

        val sums = items.sumRequestsBySession(listOf("s1", "s2")).associate { it.sessionId to it.requests }

        assertEquals(mapOf("s1" to 200L, "s2" to 0L), sums)
    }

    @Test
    fun `a watermark keeps its catch-up cursor and reads back without one once it is cleared`() {
        watermarks.saveAndFlush(AutomationWatermarkEntity(WALLET, 1, "sig-1", 2, -1, EVENING).apply {
            resumeBefore = "sig-0"
            pending = DiscoveryCursorColumns(9, "sig-9", 2, -1)
        })
        val stored = records.toDiscoveryWatermark(watermarks.findOneByWallet(WALLET) ?: error("saved"))
        watermarks.findOneByWallet(WALLET)?.let { it.resumeBefore = null; it.pending = null; watermarks.saveAndFlush(it) }

        assertEquals(DiscoveryCursor(9, "sig-9", 2, -1), stored.pending)
        assertEquals("sig-0", stored.resumeBefore)
        assertNull(records.toDiscoveryWatermark(watermarks.findOneByWallet(WALLET) ?: error("saved")).pending)
    }

    @Test
    fun `a watermark keeps the start of the scan that began its catch-up and the wallets catching up are listed`() {
        watermarks.saveAndFlush(AutomationWatermarkEntity(WALLET, 1, "sig-1", 2, -1, EVENING).apply {
            resumeBefore = "sig-0"
            catchUpStartedAt = EVENING.plusSeconds(600)
        })
        watermarks.saveAndFlush(AutomationWatermarkEntity("other-wallet", 1, "sig-1", 2, -1, EVENING))

        assertEquals(EVENING.plusSeconds(600), watermarks.findOneByWallet(WALLET)?.catchUpStartedAt)
        assertEquals(listOf(WALLET), watermarks.findWalletsCatchingUp())
    }

    @Test
    fun `items and sweep runs move to another session in bulk, by session or by item, bumping item versions`() {
        items.saveAllAndFlush(listOf(item(1, "from"), item(2, "from"), item(3, "other")))
        sweepRuns.saveAllAndFlush(listOf(sweepRun("run-1", "item-1", "V00", sessionId = "from"), sweepRun("run-3", "item-3", "V00", sessionId = "other")))
        val versions = items.findAll().associate { it.id to it.version }

        val (movedItems, movedRuns) = requireNotNull(TransactionTemplate(transactionManager).execute {
            items.moveItems(listOf("item-3"), "split")
            sweepRuns.moveItems(listOf("item-3"), "split")
            items.moveSession("from", "into") to sweepRuns.moveSession("from", "into")
        })

        assertEquals(2 to 1, movedItems to movedRuns)
        val stored = items.findAll().associateBy { it.id }
        assertEquals(mapOf("item-1" to "into", "item-2" to "into", "item-3" to "split"), stored.mapValues { it.value.sessionId })
        assertTrue(stored.all { (id, item) -> (item.version ?: 0) > (versions.getValue(id) ?: 0) })
        assertEquals(mapOf("run-1" to "into", "run-3" to "split"), sweepRuns.findAll().associate { it.itemId.replace("item", "run") to it.sessionId })
        assertEquals(listOf("item-1", "item-2"), items.findBuysBySessionId("into").map { it.id })
    }

    @Test
    fun `item counts by session and state come back grouped`() {
        items.saveAll(listOf(
            item(1, "s1"), item(2, "s1"), item(3, "s1", AutomationItemState.FAILED), item(4, "s2"), item(5, "s3"),
        ))

        val counts = items.countBySessionAndState(listOf("s1", "s2")).map { Triple(it.sessionId, it.state, it.items) }.toSet()

        assertEquals(setOf(
            Triple("s1", AutomationItemState.DISCOVERED, 2L), Triple("s1", AutomationItemState.FAILED, 1L), Triple("s2", AutomationItemState.DISCOVERED, 1L),
        ), counts)
    }

    @Test
    fun `idle open sessions of a wallet are found by their last buy, one page at a time`() {
        sessions.saveAll(listOf(
            session("s1", AutomationSessionState.OPEN, EVENING.minus(Duration.ofHours(3))),
            session("s2", AutomationSessionState.OPEN, EVENING.minus(Duration.ofHours(2))),
            session("s3", AutomationSessionState.OPEN, EVENING),
            session("s4", AutomationSessionState.CLOSING, EVENING.minus(Duration.ofHours(4))),
        ))

        assertEquals(listOf("s1"), sessions.findIdsByWalletAndStateAndLastBuyAtBefore(WALLET, AutomationSessionState.OPEN, EVENING.minusSeconds(1), PageRequest.of(0, 1)))
        assertEquals(listOf("s1", "s2"), sessions.findIdsByWalletAndStateAndLastBuyAtBefore(WALLET, AutomationSessionState.OPEN, EVENING, PageRequest.of(0, 10)))
        assertEquals(emptyList(), sessions.findIdsByWalletAndStateAndLastBuyAtBefore("other", AutomationSessionState.OPEN, EVENING, PageRequest.of(0, 10)))
    }

    @Test
    fun `a watermark keeps the start of live coverage and reads it back, null for a row stored without one`() {
        watermarks.saveAndFlush(AutomationWatermarkEntity(WALLET, 1, "sig-1", 2, -1, EVENING).apply { coveredFrom = EVENING.minus(Duration.ofHours(12)) })
        watermarks.saveAndFlush(AutomationWatermarkEntity("other-wallet", 1, "sig-1", 2, -1, EVENING))

        assertEquals(EVENING.minus(Duration.ofHours(12)), watermarks.findOneByWallet(WALLET)?.coveredFrom)
        assertEquals(EVENING.minus(Duration.ofHours(12)), records.toDiscoveryWatermark(watermarks.findOneByWallet(WALLET) ?: error("saved")).coveredFrom)
        assertNull(watermarks.findOneByWallet("other-wallet")?.coveredFrom)
    }

    @Test
    fun `a trading day's sessions are those whose first buy is in its range, the start included and the end excluded, oldest first`() {
        val start = Instant.parse("2026-11-03T07:00:00Z")
        val end = start.plus(Duration.ofHours(24))
        sessions.saveAll(listOf(
            session("before", AutomationSessionState.CLOSED, start.minusMillis(1)),
            session("at-start", AutomationSessionState.CLOSED, start),
            session("inside", AutomationSessionState.CLOSED, start.plus(Duration.ofHours(10))),
            session("at-end", AutomationSessionState.CLOSED, end),
            session("other-wallet", AutomationSessionState.CLOSED, start.plusSeconds(5)).apply { wallet = "other" },
        ))

        assertEquals(listOf("at-start", "inside"), sessions.findStartedIn(WALLET, start, end).map { it.id })
    }

    @Test
    fun `the items and states of several sessions are read with their session in one query`() {
        items.saveAll(listOf(item(1, "s1", blockTime = EVENING), item(2, "s2", AutomationItemState.FAILED, blockTime = EVENING.plusSeconds(1)), item(3, "s3")))

        val rows = items.findStatesBySessionIdIn(listOf("s1", "s2"))

        assertEquals(setOf(Triple("item-1", "s1", AutomationItemState.DISCOVERED), Triple("item-2", "s2", AutomationItemState.FAILED)),
            rows.map { Triple(it.id, it.sessionId, it.state) }.toSet())
    }

    @Test
    fun `a new sweep run with the id of a stored one is inserted and refused, never merged over it`() {
        sweepRuns.saveAndFlush(sweepRun("run-a", "item-1", "V00", outcome = "MARKET_COVERAGE_ENDED"))

        assertFailsWith<DataIntegrityViolationException> { sweepRuns.saveAndFlush(sweepRun("run-a", "item-1", "V00", outcome = "NO_DIP", runId = "run")) }
        assertEquals("MARKET_COVERAGE_ENDED", sweepRuns.findAll().single().outcome)
    }

    @Test
    fun `a stored sweep run read back is updated in place, not inserted again`() {
        sweepRuns.saveAndFlush(sweepRun("run-a", "item-1", "V00"))
        val stored = sweepRuns.findAll().single().apply { sessionId = "merged" }

        sweepRuns.saveAndFlush(stored)

        assertEquals(listOf("merged"), sweepRuns.findAll().map { it.sessionId })
    }

    @Test
    fun `a second backfill with the same request id is refused`() {
        backfills.saveAndFlush(backfill("a", requestId = "req-1", active = null))

        assertFailsWith<DataIntegrityViolationException> { backfills.saveAndFlush(backfill("b", requestId = "req-1", active = null)) }
    }

    @Test
    fun `a wallet has one active backfill, and any number that ended`() {
        backfills.saveAndFlush(backfill("a", requestId = "req-1", active = WALLET))

        assertFailsWith<DataIntegrityViolationException> { backfills.saveAndFlush(backfill("b", requestId = "req-2", active = WALLET)) }

        backfills.saveAndFlush(backfill("c", requestId = "req-3", active = null))
        backfills.saveAndFlush(backfill("d", requestId = "req-4", active = null))
        assertEquals(3, backfills.count())
    }

    @Test
    fun `a backfill and the backfill columns of items and sessions round-trip`() {
        val saved = backfill("a", requestId = "req-1", active = WALLET).also {
            it.minSignalLamports = BigInteger.valueOf(2_500_000_000)
            it.mints = """["m1","m2"]"""
            it.resumeBefore = "sig-9"
            it.summary = "{}"
            it.estimatedRequests = 1234
            it.discoveryRequests = 77
            it.buysPassing = 5
            it.finishedAt = EVENING
            it.scannedTo = Instant.parse("2026-10-29T20:00:00Z")
            it.skippedNoBlockTime = 3
            it.filtered = true
        }
        backfills.saveAndFlush(saved)
        sessions.saveAndFlush(session("s1", AutomationSessionState.OPEN, EVENING).also { it.backfillId = "a" })
        items.saveAndFlush(item(1, "s1").also { it.backfillId = "a" })

        val found = backfills.findOneById("a")!!.let(records::toBackfill)

        assertEquals(LocalDate.parse("2026-10-29"), found.fromDay)
        assertEquals(BackfillState.QUEUED, found.state)
        assertEquals(BigInteger.valueOf(2_500_000_000), found.minSignalLamports)
        assertEquals(listOf("m1", "m2"), found.mints)
        assertEquals("sig-9", found.resumeBefore)
        assertEquals(1234L, found.estimatedRequests)
        assertEquals(77L, found.discoveryRequests)
        assertEquals(EVENING, found.finishedAt)
        assertEquals(Instant.parse("2026-10-29T20:00:00Z"), found.scannedTo)
        assertEquals(3, found.skippedNoBlockTime)
        assertEquals(true, found.filtered)
        assertEquals("a", sessions.findById("s1").orElseThrow().backfillId)
        assertEquals("a", items.findById("item-1").orElseThrow().backfillId)
    }

    @Test
    fun `due items are the live ones first, then those of a backfill whose scan is over, each oldest buy first`() {
        sessions.saveAll(listOf(session("live", AutomationSessionState.OPEN, EVENING), session("bf", AutomationSessionState.CLOSING, EVENING.minusSeconds(86400))))
        backfills.saveAll(listOf(
            backfill("capturing", requestId = "r1", active = WALLET, state = BackfillState.CAPTURING),
            backfill("scanning", requestId = "r2", active = null, state = BackfillState.SCANNING),
            backfill("stopped", requestId = "r3", active = null, state = BackfillState.STOPPED_BUDGET),
            backfill("cancelled", requestId = "r4", active = null, state = BackfillState.CANCELLED),
        ))
        items.saveAll(listOf(
            item(1, "live", blockTime = EVENING.plusSeconds(60), nextActionAt = EVENING),
            item(2, "bf", blockTime = EVENING.minusSeconds(86400), nextActionAt = EVENING).also { it.backfillId = "capturing" },
            item(3, "bf", blockTime = EVENING.minusSeconds(90000), nextActionAt = EVENING).also { it.backfillId = "stopped" },
            item(4, "bf", blockTime = EVENING.minusSeconds(95000), nextActionAt = EVENING).also { it.backfillId = "scanning" },
            item(5, "bf", blockTime = EVENING.minusSeconds(96000), nextActionAt = EVENING).also { it.backfillId = "cancelled" },
            item(6, "live", blockTime = EVENING.plusSeconds(30), nextActionAt = EVENING),
        ))
        val pending = AutomationItemState.entries.filterNot { it.terminal }

        val due = items.findDue(pending, BackfillState.CAPTURING_STATES, EVENING, PageRequest.of(0, 10)).map { it.id }

        assertEquals(listOf("item-6", "item-1", "item-3", "item-2"), due, "an item of a scanning or cancelled backfill is not due")
    }

    @Test
    fun `a backfill's sessions are not among the live sessions the idle rule and live placement read`() {
        sessions.saveAll(listOf(
            session("live", AutomationSessionState.OPEN, EVENING.minusSeconds(7200)),
            session("bf", AutomationSessionState.OPEN, EVENING.minusSeconds(7200)).also { it.backfillId = "a" },
        ))

        assertEquals(listOf("live"), sessions.findAllByWalletAndBackfillIdIsNullAndStateOrderByCreatedAtAscFirstBuyAtAscIdAsc(WALLET, AutomationSessionState.OPEN).map { it.id })
        assertEquals(listOf("bf"), sessions.findAllByBackfillIdAndStateOrderByCreatedAtAscFirstBuyAtAscIdAsc("a", AutomationSessionState.OPEN).map { it.id })
        assertEquals(listOf("live"), sessions.findIdsByWalletAndStateAndLastBuyAtBefore(WALLET, AutomationSessionState.OPEN, EVENING, PageRequest.of(0, 10)))
        assertEquals(1L, sessions.countWithLastBuyIn(WALLET, AutomationSessionState.OPEN, null, EVENING))
        assertEquals("live", sessions.findFirstByWalletAndBackfillIdIsNullAndStateOrderByLastBuyAtDesc(WALLET, AutomationSessionState.OPEN)?.id)
    }

    @Test
    fun `overlapping capturing backfills are found only while active or done and unfiltered, never touching ones, dry runs, filtered, stopped or cancelled ones`() {
        val start = Instant.parse("2026-10-29T07:00:00Z")
        val end = start.plusSeconds(86400)
        backfills.saveAll(listOf(
            backfill("done", requestId = "r1", active = null, state = BackfillState.DONE, from = start, to = end),
            backfill("dry", requestId = "r2", active = null, state = BackfillState.DONE, from = start, to = end).also { it.dryRun = true },
            backfill("cancelled", requestId = "r3", active = null, state = BackfillState.CANCELLED, from = start, to = end).also { it.itemsCreated = 2 },
            backfill("stopped", requestId = "r4", active = null, state = BackfillState.STOPPED_BUDGET, from = start, to = end).also { it.itemsCreated = 2 },
            backfill("capturing", requestId = "r5", active = WALLET, state = BackfillState.CAPTURING, from = start, to = end),
            backfill("filtered", requestId = "r6", active = null, state = BackfillState.DONE, from = start, to = end).also { it.filtered = true },
        ))

        val found = backfills.findCapturingOverlapping(WALLET, start.plusSeconds(3600), start.plusSeconds(7200), PageRequest.of(0, 10)).map { it.id }
        val touching = backfills.findCapturingOverlapping(WALLET, end, end.plusSeconds(3600), PageRequest.of(0, 10))

        assertEquals(setOf("done", "capturing"), found.toSet())
        assertTrue(touching.isEmpty())
    }

    @Test
    fun `the ranges an ended unfiltered backfill covers run from where its scan reached, and a filtered or running one covers none`() {
        val start = Instant.parse("2026-10-29T07:00:00Z")
        val end = start.plusSeconds(86400)
        val reached = start.plusSeconds(7200)
        backfills.saveAll(listOf(
            backfill("stopped", requestId = "r1", active = null, state = BackfillState.STOPPED_BUDGET, from = start, to = end).also { it.scannedTo = reached },
            backfill("done", requestId = "r2", active = null, state = BackfillState.DONE, from = start.minusSeconds(86400), to = start),
            backfill("filtered", requestId = "r3", active = null, state = BackfillState.DONE, from = start, to = end).also { it.filtered = true },
            backfill("running", requestId = "r4", active = WALLET, state = BackfillState.CAPTURING, from = start, to = end).also { it.scannedTo = start },
        ))

        val ranges = backfills.findDiscoveredRanges(WALLET, PageRequest.of(0, 10)).map { it.rangeStart to it.rangeEnd }

        assertEquals(listOf(start.minusSeconds(86400) to start, reached to end), ranges)
    }

    private fun backfill(
        id: String,
        requestId: String,
        active: String?,
        state: BackfillState = BackfillState.QUEUED,
        from: Instant = Instant.parse("2026-10-29T07:00:00Z"),
        to: Instant = Instant.parse("2026-10-31T07:00:00Z"),
    ) = AutomationBackfillEntity(id, requestId, WALLET, active, LocalDate.parse("2026-10-29"), LocalDate.parse("2026-10-30"), from, to, false, null, null, 5000, state, EVENING, EVENING)
}
