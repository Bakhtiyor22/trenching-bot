package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationFixtures.CAPTURED_LAMPORTS
import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationFixtures.SKIPPED_LAMPORTS
import com.trenching.bot.console.automation.AutomationFixtures.WALLET
import com.trenching.bot.console.automation.AutomationFixtures.buy
import com.trenching.bot.console.automation.AutomationFixtures.mint
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.AutomationFixtures.sell
import com.trenching.bot.console.automation.AutomationFixtures.signature
import com.trenching.bot.console.automation.AutomationRows.item
import com.trenching.bot.console.automation.AutomationRows.session
import com.trenching.bot.console.automation.application.AutomationBackfillDiscoveryService
import com.trenching.bot.console.automation.application.AutomationBackfillService
import com.trenching.bot.console.automation.application.AutomationCoverage
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.domain.AutomationDisabledException
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.automation.domain.BackfillActiveException
import com.trenching.bot.console.automation.domain.BackfillNotFoundException
import com.trenching.bot.console.automation.domain.BackfillOverlapsCoverageException
import com.trenching.bot.console.automation.domain.BackfillRequest
import com.trenching.bot.console.automation.domain.BackfillScanOutcome
import com.trenching.bot.console.automation.domain.BackfillState
import com.trenching.bot.console.automation.domain.CaptureDisabledException
import com.trenching.bot.console.automation.domain.DryRunSummary
import com.trenching.bot.console.automation.domain.InvalidAutomationRequestException
import com.trenching.bot.console.automation.domain.SessionCloseReason
import com.trenching.bot.console.automation.domain.TimeRange
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkEntity
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import java.math.BigDecimal
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.transaction.IllegalTransactionStateException

/**
 * Plan Key Decisions 6-10, Rules 35-43 and Acceptance Criteria 5-9: creating, scanning, budgeting, settling and cancelling a
 * backfill against PostgreSQL, with a fake wallet listing. Live coverage starts at [coveredFrom] (the start of the trading day of
 * 31 October); the backfills read 29 and 30 October unless a test says otherwise.
 */
class AutomationBackfillServiceTest : AutomationDatabaseTest() {
    private val clock = MutableClock(EVENING)
    private val lookup = FakeWalletActivityLookup()
    private val usage = FakeApiUsageReports()
    private val properties = properties(captureEnabled = true)

    private val rangeStart = Instant.parse("2026-10-29T07:00:00Z")
    private val coveredFrom = Instant.parse("2026-10-31T07:00:00Z")
    private val service: AutomationBackfillService by lazy { backfillService(properties, clock) }
    private val query by lazy { backfillQuery(properties) }

    @Test
    fun `a capturing backfill is queued holding the wallet's active slot, with its end clipped to live coverage`() {
        live()

        val created = service.create(request(to = "2026-11-01")).backfill

        assertEquals(AutomationIds.backfill(WALLET, "req-1"), created.id)
        assertEquals(BackfillState.QUEUED, created.state)
        assertEquals(LocalDate.parse("2026-10-29"), created.fromDay)
        assertEquals(LocalDate.parse("2026-11-01"), created.toDay)
        assertEquals(TimeRange(rangeStart, coveredFrom), TimeRange(created.rangeStart, created.rangeEnd), "the partial day before live coverage is still filled")
        assertEquals(WALLET, stored(created.id).activeWallet)
        assertEquals(EVENING, created.createdAt)
    }

    @Test
    fun `a capturing backfill that does not end before live coverage, or finds none, is refused`() {
        live()
        val beyond = assertFailsWith<BackfillOverlapsCoverageException> { service.create(request(from = "2026-10-31", to = "2026-11-01")) }
        assertEquals(coveredFrom, beyond.coveredFrom)

        watermarks.deleteAllInBatch()
        live(coveredFrom = null)
        assertFailsWith<BackfillOverlapsCoverageException> { service.create(request()) }

        watermarks.deleteAllInBatch()
        assertFailsWith<BackfillOverlapsCoverageException> { service.create(request()) }
        assertEquals(0, backfills.count())
    }

    @Test
    fun `a dry run may cover any range, live coverage or none, and is not clipped`() {
        live(coveredFrom = null)

        val created = service.create(request(from = "2026-10-31", to = "2026-11-01", dryRun = true)).backfill

        assertEquals(TimeRange(coveredFrom, Instant.parse("2026-11-02T07:00:00Z")), TimeRange(created.rangeStart, created.rangeEnd))
    }

    @Test
    fun `a capturing backfill needs capture enabled, a dry run does not`() {
        live()
        val noCapture = backfillService(properties(captureEnabled = false), clock)

        assertFailsWith<CaptureDisabledException> { noCapture.create(request()) }
        assertEquals(BackfillState.QUEUED, noCapture.create(request(requestId = "req-2", dryRun = true)).backfill.state)
    }

    @Test
    fun `nothing is created while automation is off`() {
        assertFailsWith<AutomationDisabledException> { backfillService(AutomationProperties(), clock).create(request()) }
    }

    @Test
    fun `a repeated create returns the stored backfill even while automation is off, a new one is still refused`() {
        live()
        val first = service.create(request()).backfill
        val off = backfillService(AutomationProperties(wallet = WALLET), clock)

        val again = off.create(request(from = "2026-10-20", to = "2026-10-21"))

        assertEquals(first, again.backfill)
        assertFailsWith<AutomationDisabledException> { off.create(request(requestId = "req-2")) }
        assertEquals(1, backfills.count())
    }

    @Test
    fun `an invalid request is refused on its field and stores nothing`() {
        live()

        val failure = assertFailsWith<InvalidAutomationRequestException> { service.create(request(budget = 99)) }

        assertEquals("requestBudget", failure.field)
        assertEquals(0, backfills.count())
    }

    @Test
    fun `a capturing backfill overlapping another one is refused naming it, and one that only touches it is free`() {
        live()
        val first = service.create(request()).backfill
        end(first.id, BackfillState.DONE)

        val refused = assertFailsWith<BackfillOverlapsCoverageException> { service.create(request(requestId = "req-2", from = "2026-10-28", to = "2026-10-29")) }

        assertEquals(first.id, refused.overlappingId)
        assertEquals(BackfillState.QUEUED, service.create(request(requestId = "req-3", from = "2026-10-27", to = "2026-10-28")).backfill.state)
    }

    @Test
    fun `a stopped or cancelled backfill never blocks a capturing backfill over its range, even when it created items`() {
        live()
        val first = service.create(request()).backfill
        stored(first.id).also { it.itemsCreated = 3 }.let(backfills::saveAndFlush)
        val overlap = request(requestId = "req-2", from = "2026-10-28", to = "2026-10-29")

        end(first.id, BackfillState.CANCELLED)
        val afterCancel = service.create(overlap).backfill
        end(afterCancel.id, BackfillState.STOPPED_BUDGET)
        val afterStop = service.create(request(requestId = "req-3", from = "2026-10-28", to = "2026-10-29")).backfill

        assertEquals(BackfillState.QUEUED, afterCancel.state)
        assertEquals(BackfillState.QUEUED, afterStop.state)
    }

    @Test
    fun `a dry run, finished or not, never blocks a capturing backfill`() {
        live()
        val dry = service.create(request(dryRun = true)).backfill
        end(dry.id, BackfillState.DONE)

        assertEquals(BackfillState.QUEUED, service.create(request(requestId = "req-2")).backfill.state)
    }

    @Test
    fun `a second active backfill is refused first, naming the active one, even when its range would overlap`() {
        live()
        val first = service.create(request()).backfill

        val refused = assertFailsWith<BackfillActiveException> { service.create(request(requestId = "req-2")) }

        assertEquals(first.id, refused.activeId)
        assertEquals(1, backfills.count())
    }

    @Test
    fun `a dry run holds the active slot too`() {
        live()
        service.create(request(dryRun = true))

        assertFailsWith<BackfillActiveException> { service.create(request(requestId = "req-2", from = "2026-10-20", to = "2026-10-21", dryRun = true)) }
    }

    @Test
    fun `the same request id returns the stored backfill, whatever else was sent and wherever it has got to`() {
        live()
        val first = service.create(request()).backfill
        end(first.id, BackfillState.DONE)

        val again = service.create(request(from = "2026-10-20", to = "2026-10-21", budget = 300, dryRun = true)).backfill

        assertEquals(first.id, again.id)
        assertEquals(BackfillState.DONE, again.state)
        assertEquals(first.rangeStart, again.rangeStart)
        assertEquals(1, backfills.count())
    }

    @Test
    fun `a dry run reads its range and writes no item or session, only its counts, summary and estimate`() {
        live()
        items.saveAll(listOf(captured(1, 100), captured(2, 200), captured(3, 400)))
        sessions.save(session("other-night", AutomationSessionState.CLOSED, Instant.parse("2026-11-01T14:00:00Z")))
        items.saveAll(listOf(
            item(31, "other-night", AutomationItemState.SIMULATED, blockTime = Instant.parse("2026-11-01T14:00:00Z"), mint = 1, signature = 531),
            item(32, "other-night", AutomationItemState.ABANDONED, blockTime = Instant.parse("2026-11-01T14:00:00Z"), mint = 2, signature = 532).also { it.backfillId = "earlier" },
            item(33, "other-night", AutomationItemState.SIMULATED, blockTime = Instant.parse("2026-11-01T14:00:00Z"), mint = 6, signature = 533),
        ))
        lookup.actions += listOf(
            buyAt(11, "2026-10-29T14:00:00Z", mint = 1), buyAt(12, "2026-10-29T14:10:00Z", mint = 2),
            buyAt(13, "2026-10-30T14:00:00Z", mint = 3), buyAt(14, "2026-10-30T14:05:00Z", mint = 4),
            buyAt(15, "2026-10-30T14:10:00Z", mint = 1), buyAt(16, "2026-10-30T14:20:00Z", SKIPPED_LAMPORTS, mint = 6),
            sell(17, Instant.parse("2026-10-30T14:30:00Z").epochSecond, Instant.parse("2026-10-30T14:30:00Z"), mint = 3), buyAt(18, "2026-10-29T04:00:00Z", mint = 9),
        )
        val created = service.create(request(dryRun = true)).backfill

        val outcome = backfillDiscovery(lookup, properties, clock, usage).scan()

        val done = query.get(created.id).backfill
        assertEquals(BackfillState.DONE, done.state)
        assertEquals(BackfillState.DONE, (outcome as BackfillScanOutcome.Recorded).record.state)
        assertEquals(7, done.actionsScanned, "the actions inside the range")
        assertEquals(6, done.buysFound)
        assertEquals(5, done.buysPassing, "one buy is under the minimum size")
        assertEquals(0, done.itemsCreated)
        assertEquals(DryRunSummary(4, mapOf(LocalDate.parse("2026-10-29") to 2, LocalDate.parse("2026-10-30") to 3)), done.summary)
        assertEquals(5, done.buysPassing)
        assertEquals(600L, done.estimatedRequests, "the median of 100, 200 and 400 is 200, times the three mints without a result: mint 1 has a simulated item, mint 2's was abandoned and a backfill takes it over")
        assertEquals(8, done.discoveryRequests)
        assertNull(done.resumeBefore)
        assertNull(stored(created.id).activeWallet)
        assertEquals(EVENING, done.finishedAt)
        assertEquals(6, items.count(), "only the items seeded above")
        assertEquals(1, sessions.count(), "only the session seeded above")
    }

    @Test
    fun `a dry run has no estimate while the wallet has no captured item`() {
        live()
        lookup.actions += buyAt(11, "2026-10-30T14:00:00Z")
        val created = service.create(request(dryRun = true)).backfill

        backfillDiscovery(lookup, properties, clock, usage).scan()

        val done = query.get(created.id).backfill
        assertEquals(1, done.buysPassing)
        assertNull(done.estimatedRequests)
    }

    @Test
    fun `the scan enters the listing after the wallet's earliest stored buy at or past the range end and skips what lies between`() {
        live()
        items.save(item(900, "live-1", blockTime = Instant.parse("2026-11-01T14:00:00Z"), signature = 900))
        lookup.actions += buyAt(900, "2026-11-01T14:00:00Z")
        lookup.actions += buyAt(901, "2026-10-31T10:00:00Z")
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z")
        lookup.actions += buyAt(2, "2026-10-29T14:00:00Z")
        lookup.actions += buyAt(3, "2026-10-29T04:00:00Z")
        service.create(request())

        backfillDiscovery(lookup, properties, clock, usage).scan()

        assertEquals(signature(900), lookup.requests.first().first.before, "no page is spent on what live discovery already read")
        assertEquals(setOf(signature(1).value, signature(2).value), items.findAll().filter { it.backfillId != null }.map { it.signature }.toSet())
    }

    @Test
    fun `with no stored buy to enter after, the scan starts at the newest transaction`() {
        live()
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z")
        service.create(request())

        backfillDiscovery(lookup, properties, clock, usage).scan()

        assertNull(lookup.requests.first().first.before)
    }

    @Test
    fun `the scan stops at the range start, tags its items and sessions, and closes the sessions as it ends`() {
        live()
        lookup.actions += buyAt(1, "2026-10-29T14:00:00Z")
        lookup.actions += buyAt(2, "2026-10-29T14:20:00Z")
        lookup.actions += buyAt(3, "2026-10-30T14:00:00Z")
        lookup.actions += buyAt(4, "2026-10-29T06:59:00Z")
        val created = service.create(request()).backfill

        backfillDiscovery(lookup, properties, clock, usage).scan()

        val found = backfills.findById(created.id).orElseThrow()
        assertEquals(BackfillState.CAPTURING, found.state)
        assertEquals(WALLET, found.activeWallet, "capture is still to come")
        assertNull(found.resumeBefore)
        assertEquals(3, found.itemsCreated)
        assertEquals(3, items.count())
        assertTrue(items.findAll().all { it.backfillId == created.id && it.state == AutomationItemState.DISCOVERED })
        val stored = sessions.findAll().sortedBy { it.firstBuyAt }
        assertEquals(2, stored.size, "the two buys of 29 October are one session, the buy of 30 October another")
        assertTrue(stored.all { it.backfillId == created.id && it.state == AutomationSessionState.CLOSING })
        assertEquals(EVENING, found.updatedAt)
    }

    @Test
    fun `a scan limited to one page a tick continues from its cursor, and a session spans the ticks while the live idle rule leaves it alone`() {
        live()
        val limited = properties(captureEnabled = true, maxDiscoveryPages = 1)
        lookup.actions += (1..45).map { buyAt(it, Instant.parse("2026-10-30T14:00:00Z").plusSeconds(60L * it)) }
        val created = service.create(request()).backfill
        val scanner = backfillDiscovery(lookup, limited, clock, usage)

        scanner.scan()
        val afterFirst = stored(created.id)
        sessionService(limited, clock).closeIdleSessions()
        val stillOpen = sessions.findAll().single().state
        scanner.scan()
        scanner.scan()

        assertEquals(BackfillState.SCANNING, afterFirst.state)
        assertEquals(AutomationSessionState.OPEN, stillOpen, "the live idle rule leaves a backfill's sessions to its own scan")
        assertEquals(signature(26).value, afterFirst.resumeBefore, "the oldest transaction of the first page of twenty")
        assertEquals(20, afterFirst.itemsCreated)
        assertEquals(listOf(null, signature(26), signature(6)), lookup.requests.map { it.first.before })
        assertEquals(BackfillState.CAPTURING, stored(created.id).state)
        assertEquals(45, items.count())
        assertEquals(1, sessions.count(), "buys a minute apart are one session, whichever tick found them")
        assertEquals(AutomationSessionState.CLOSING, sessions.findAll().single().state)
    }

    @Test
    fun `an earlier buy of a mint found by a later tick takes the mint's item from the later buy`() {
        live()
        val limited = properties(captureEnabled = true, maxDiscoveryPages = 1)
        lookup.actions += (1..20).map { buyAt(it, Instant.parse("2026-10-30T14:00:00Z").plusSeconds(60L * it), mint = it + 100) }
        lookup.actions += buyAt(21, "2026-10-30T13:00:00Z", mint = 150)
        lookup.actions += buyAt(22, "2026-10-30T14:30:00Z", mint = 21 + 100)
        lookup.actions += buyAt(23, "2026-10-30T12:00:00Z", mint = 121)
        service.create(request())
        val scanner = backfillDiscovery(lookup, limited, clock, usage)

        repeat(2) { scanner.scan() }

        val byMint = items.findAll().groupBy { it.mint }
        assertTrue(byMint.values.all { it.size == 1 })
        assertEquals(signature(23).value, byMint.getValue(mint(121).value).single().signature, "the earliest buy of the mint is its item")
        assertEquals(22, items.count())
    }

    @Test
    fun `a provider that cannot be read skips the tick and leaves the cursor and the spend as they were`() {
        live()
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z")
        val created = service.create(request()).backfill
        val scanner = backfillDiscovery(lookup, properties, clock, usage)

        lookup.unavailable = WalletActivityUnavailable.PROVIDER_RATE_LIMITED
        val skipped = scanner.scan()
        lookup.unavailable = null
        val unchanged = stored(created.id)
        val retried = scanner.scan()

        assertEquals(BackfillScanOutcome.Unavailable(WalletActivityUnavailable.PROVIDER_RATE_LIMITED), skipped)
        assertEquals(BackfillState.QUEUED, unchanged.state)
        assertEquals(0, unchanged.discoveryRequests)
        assertEquals(BackfillState.CAPTURING, (retried as BackfillScanOutcome.Recorded).record.state)
        assertEquals(1, items.count())
    }

    @Test
    fun `the token list limits the items to its mints and counts the other buys that pass the filter as skipped`() {
        live()
        lookup.actions += listOf(
            buyAt(1, "2026-10-30T14:00:00Z", mint = 1), buyAt(2, "2026-10-30T14:01:00Z", mint = 2), buyAt(3, "2026-10-30T14:02:00Z", mint = 3),
            buyAt(4, "2026-10-30T14:03:00Z", SKIPPED_LAMPORTS, mint = 4),
        )
        val created = service.create(request(mints = listOf(mint(1).value, mint(3).value))).backfill

        backfillDiscovery(lookup, properties, clock, usage).scan()

        val done = query.get(created.id).backfill
        assertEquals(setOf(mint(1).value, mint(3).value), items.findAll().map { it.mint }.toSet())
        assertEquals(2, done.itemsCreated)
        assertEquals(1, done.skippedByFilter)
        assertEquals(2, done.buysPassing)
        assertEquals(4, done.buysFound)
        assertEquals(1, sessions.findAll().single().belowFilterCount, "the buy under the minimum is counted on its session, as live discovery does")
    }

    @Test
    fun `a minimum signal replaces the capture minimum for this backfill`() {
        live()
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z", 3_000_000_000, mint = 1)
        lookup.actions += buyAt(2, "2026-10-30T14:01:00Z", CAPTURED_LAMPORTS, mint = 2)
        service.create(request(minSignalSol = BigDecimal("2.5")))

        backfillDiscovery(lookup, properties, clock, usage).scan()

        assertEquals(listOf(mint(1).value), items.findAll().map { it.mint })
    }

    @Test
    fun `an earlier buy found by a backfill replaces a live item of the mint while that item is discovered with no capture step`() {
        live()
        sessions.save(session("live-1", AutomationSessionState.CLOSED, Instant.parse("2026-11-01T14:00:00Z")))
        items.save(item(7, "live-1", blockTime = Instant.parse("2026-11-01T14:00:00Z"), mint = 5, signature = 700).also { it.slot = LIVE_SLOT })
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z", mint = 5)
        service.create(request())

        backfillDiscovery(lookup, properties, clock, usage).scan()

        val kept = items.findAll().single()
        assertEquals(signature(1).value, kept.signature)
        assertNotNull(kept.backfillId)
        assertEquals(0, sessions.findAll().count { it.id == "live-1" }, "the live session its only item left is removed")
    }

    @Test
    fun `an earlier buy found by a backfill leaves a live item that capture has started, and the backfill creates no item for the mint`() {
        live()
        sessions.save(session("live-1", AutomationSessionState.CLOSED, Instant.parse("2026-11-01T14:00:00Z")))
        items.save(item(7, "live-1", AutomationItemState.CAPTURING, blockTime = Instant.parse("2026-11-01T14:00:00Z"), mint = 5, signature = 700)
            .also { it.slot = LIVE_SLOT; it.step = AutomationStep.WALLET })
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z", mint = 5)
        val created = service.create(request()).backfill

        backfillDiscovery(lookup, properties, clock, usage).scan()

        assertEquals("item-7", items.findAll().single().id)
        assertEquals(0, stored(created.id).itemsCreated)
        assertEquals(BackfillState.CAPTURING, stored(created.id).state)
    }

    @Test
    fun `discovery that would pass the budget stops the scan with the budget exhausted, keeping the items already found`() {
        live()
        lookup.actions += (1..120).map { buyAt(it, Instant.parse("2026-10-30T10:00:00Z").plusSeconds(60L * it)) }
        lookup.pageRequests = 400
        val created = service.create(request(budget = 1222)).backfill

        val outcome = backfillDiscovery(lookup, properties, clock, usage).scan()

        val stopped = stored(created.id)
        assertEquals(BackfillState.STOPPED_BUDGET, (outcome as BackfillScanOutcome.Recorded).record.state)
        assertEquals(BackfillState.STOPPED_BUDGET, stopped.state)
        assertEquals(1600, stopped.discoveryRequests, "four pages of 400; a fifth might cost 22 and pass 1,222, though the fourth was admitted at 1,200")
        assertEquals(signature(41).value, stopped.resumeBefore)
        assertNull(stopped.activeWallet)
        assertEquals(80, items.count())
        assertTrue(sessions.findAll().all { it.state == AutomationSessionState.CLOSING })
        assertEquals(BackfillScanOutcome.NothingToScan, backfillDiscovery(lookup, properties, clock, usage).scan())
    }

    @Test
    fun `discovery pauses while the rolling total is within the reserve of the cap`() {
        live()
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z")
        val created = service.create(request()).backfill
        val scanner = backfillDiscovery(lookup, properties, clock, usage)

        usage.lastDay = properties.maxRequestsPer24h - properties.backfillReserveRequests
        val paused = scanner.scan()
        usage.lastDay -= 1
        val resumed = scanner.scan()

        assertEquals(BackfillScanOutcome.ReserveReached, paused)
        assertEquals(listOf(null), lookup.requests.map { it.first.before }, "nothing was read while paused")
        assertTrue(resumed is BackfillScanOutcome.Recorded)
        assertEquals(1, stored(created.id).itemsCreated)
    }

    @Test
    fun `a backfill whose items are all terminal is done, and live coverage moves back to its start`() {
        live()
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z")
        val created = service.create(request()).backfill
        backfillDiscovery(lookup, properties, clock, usage).scan()
        service.settle()
        assertEquals(BackfillState.CAPTURING, stored(created.id).state, "its item is not terminal")
        assertEquals(coveredFrom, watermarks.findById(WALLET).orElseThrow().coveredFrom)

        setItems(AutomationItemState.SIMULATED)
        clock.now = EVENING.plusSeconds(60)
        service.settle()

        val done = stored(created.id)
        assertEquals(BackfillState.DONE, done.state)
        assertNull(done.activeWallet)
        assertEquals(clock.now, done.finishedAt)
        assertEquals(rangeStart, watermarks.findById(WALLET).orElseThrow().coveredFrom)
    }

    @Test
    fun `an item stopped by the budget ends the backfill stopped by its budget, and the range is covered without live coverage moving`() {
        live()
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z")
        lookup.actions += buyAt(2, "2026-10-30T14:01:00Z")
        val created = service.create(request()).backfill
        backfillDiscovery(lookup, properties, clock, usage).scan()
        items.findAll().forEach { it.state = AutomationItemState.SIMULATED; items.save(it) }
        items.findAll().first().also { it.state = AutomationItemState.FAILED; it.failureCode = AutomationFailures.BACKFILL_BUDGET; items.save(it) }

        service.settle()

        assertEquals(BackfillState.STOPPED_BUDGET, stored(created.id).state)
        assertEquals(coveredFrom, watermarks.findById(WALLET).orElseThrow().coveredFrom, "only a done, unfiltered backfill moves the live floor")
        assertEquals(listOf(rangeStart), coverage.of(WALLET).ranges.map { it.from }, "its range is covered all the same, and merges with live coverage")
    }

    @Test
    fun `a backfill that found no buy is done at once and its range is covered`() {
        live()
        val created = service.create(request()).backfill
        backfillDiscovery(lookup, properties, clock, usage).scan()

        service.settle()

        assertEquals(BackfillState.DONE, stored(created.id).state)
        assertEquals(rangeStart, watermarks.findById(WALLET).orElseThrow().coveredFrom)
    }

    @Test
    fun `finished capturing backfills join live coverage, and nothing else does`() {
        live()
        backfill("done", "2026-10-29T07:00:00Z", "2026-10-30T07:00:00Z", BackfillState.DONE)
        backfill("stopped-scan-complete", "2026-10-28T07:00:00Z", "2026-10-29T07:00:00Z", BackfillState.STOPPED_BUDGET).also { it.scannedTo = it.rangeStart; backfills.save(it) }
        backfill("stopped-mid-scan", "2026-10-20T07:00:00Z", "2026-10-21T07:00:00Z", BackfillState.STOPPED_BUDGET)
            .also { it.resumeBefore = "sig"; it.scannedTo = Instant.parse("2026-10-20T19:00:00Z"); backfills.save(it) }
        backfill("cancelled-mid-scan", "2026-10-22T07:00:00Z", "2026-10-23T07:00:00Z", BackfillState.CANCELLED)
            .also { it.scannedTo = Instant.parse("2026-10-22T20:00:00Z"); backfills.save(it) }
        backfill("cancelled-before-reading", "2026-10-24T07:00:00Z", "2026-10-25T07:00:00Z", BackfillState.CANCELLED)
        backfill("capturing", "2026-10-26T07:00:00Z", "2026-10-27T07:00:00Z", BackfillState.CAPTURING).also { it.scannedTo = it.rangeStart; backfills.save(it) }
        backfill("dry", "2026-10-18T07:00:00Z", "2026-10-19T07:00:00Z", BackfillState.DONE).also { it.dryRun = true; backfills.save(it) }
        backfill("filtered-done", "2026-10-14T07:00:00Z", "2026-10-15T07:00:00Z", BackfillState.DONE).also { it.filtered = true; backfills.save(it) }
        backfill("filtered-cancelled", "2026-10-16T07:00:00Z", "2026-10-17T07:00:00Z", BackfillState.CANCELLED)
            .also { it.filtered = true; it.scannedTo = it.rangeStart; backfills.save(it) }

        val ranges = AutomationCoverage(watermarks, backfills, records).of(WALLET).ranges

        assertEquals(
            listOf(
                Instant.parse("2026-10-20T19:00:00Z") to Instant.parse("2026-10-21T07:00:00Z"),
                Instant.parse("2026-10-22T20:00:00Z") to Instant.parse("2026-10-23T07:00:00Z"),
                Instant.parse("2026-10-28T07:00:00Z") to Instant.parse("2026-10-30T07:00:00Z"),
                coveredFrom to EVENING.minusSeconds(60),
            ),
            ranges.map { it.from to it.to },
            "an ended unfiltered scan covers from where it reached to its range end, the two finished ranges touch and merge, a filtered one covers nothing, and live coverage stands apart",
        )
    }

    @Test
    fun `cancelling a scanning backfill leaves it cancelled with its items abandoned, its sessions closed and the slot free`() {
        live()
        val limited = properties(captureEnabled = true, maxDiscoveryPages = 1)
        lookup.actions += (1..45).map { buyAt(it, Instant.parse("2026-10-30T14:00:00Z").plusSeconds(60L * it)) }
        val created = service.create(request()).backfill
        backfillDiscovery(lookup, limited, clock, usage).scan()
        clock.now = EVENING.plusSeconds(30)

        val cancelled = service.cancel(created.id, "cancel-1").backfill

        assertEquals(BackfillState.CANCELLED, cancelled.state)
        assertEquals(clock.now, cancelled.finishedAt)
        assertNull(stored(created.id).activeWallet)
        assertEquals(20, items.count())
        assertTrue(items.findAll().all { it.state == AutomationItemState.ABANDONED })
        val closed = sessions.findAll().single()
        assertEquals(AutomationSessionState.CLOSED, closed.state)
        assertEquals(SessionCloseReason.MANUAL, closed.closeReason)
        assertEquals(BackfillScanOutcome.NothingToScan, backfillDiscovery(lookup, limited, clock, usage).scan(), "discovery stops")
        assertEquals(BackfillState.QUEUED, service.create(request(requestId = "req-2", from = "2026-10-20", to = "2026-10-21")).backfill.state)
    }

    @Test
    fun `cancelling again, or with another request id, or after the backfill ended, returns it unchanged`() {
        live()
        val created = service.create(request()).backfill
        val first = service.cancel(created.id, "cancel-1").backfill
        clock.now = EVENING.plusSeconds(600)

        val again = service.cancel(created.id, "cancel-1").backfill
        val other = service.cancel(created.id, "cancel-2").backfill

        assertEquals(first, again)
        assertEquals(first, other)

        val done = service.create(request(requestId = "req-2", from = "2026-10-20", to = "2026-10-21", dryRun = true)).backfill
        end(done.id, BackfillState.DONE)
        assertEquals(BackfillState.DONE, service.cancel(done.id, "cancel-3").backfill.state)
    }

    @Test
    fun `cancelling needs a valid request id and a known backfill`() {
        live()

        assertFailsWith<BackfillNotFoundException> { service.cancel("unknown", "cancel-1") }
        assertEquals("requestId", assertFailsWith<InvalidAutomationRequestException> { service.cancel("unknown", null) }.field)
    }

    @Test
    fun `reads give a page newest first and one backfill with its spend and item counts`() {
        live()
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z")
        val first = service.create(request()).backfill
        backfillDiscovery(lookup, properties, clock, usage).scan()
        items.findAll().single().also { it.requestsUsed = 300; items.save(it) }
        clock.now = EVENING.plusSeconds(60)
        end(first.id, BackfillState.DONE)
        val second = service.create(request(requestId = "req-2", from = "2026-10-20", to = "2026-10-21", dryRun = true)).backfill

        val page = query.list(0, 10)
        val detail = query.get(first.id)

        assertEquals(listOf(second.id, first.id), page.items.map { it.backfill.id })
        assertEquals(2, page.total)
        assertEquals(300, detail.itemRequests)
        assertEquals(301, detail.requestsSpent, "one request for the one transaction listed, plus the item's capture")
        assertEquals(1, detail.itemCounts.getValue(AutomationItemState.DISCOVERED))
        assertEquals(0, detail.itemCounts.getValue(AutomationItemState.SIMULATED))
        assertFailsWith<BackfillNotFoundException> { query.get("unknown") }
    }

    @Test
    fun `a capturing backfill's budget must pay for one operation and a discovery page`() {
        live()

        val failure = assertFailsWith<InvalidAutomationRequestException> { service.create(request(budget = 1221)) }

        assertEquals("requestBudget", failure.field)
        assertEquals(BackfillState.QUEUED, service.create(request(budget = 1222)).backfill.state)
    }

    @Test
    fun `an earlier buy that replaces the first item of an open live session leaves it open with the span and first item of the items left`() {
        live()
        openLiveSession("2026-11-01T14:00:00Z" to 5, "2026-11-01T14:50:00Z" to 6, "2026-11-01T15:40:00Z" to 7)
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z", mint = 5)
        service.create(request())

        backfillDiscovery(lookup, properties, clock, usage).scan()

        val rebuilt = sessions.findById("live-1").orElseThrow()
        assertEquals(AutomationSessionState.OPEN, rebuilt.state)
        assertEquals("item-801", rebuilt.firstItemId)
        assertEquals(Instant.parse("2026-11-01T14:50:00Z"), rebuilt.firstBuyAt)
        assertEquals(Instant.parse("2026-11-01T15:40:00Z"), rebuilt.lastBuyAt)
        assertEquals(setOf("item-801", "item-802"), items.findAll().filter { it.sessionId == "live-1" }.map { it.id }.toSet())
    }

    @Test
    fun `an earlier buy that replaces a middle item of an open live session splits it where the gap grew past the idle time`() {
        live()
        openLiveSession("2026-11-01T14:00:00Z" to 6, "2026-11-01T14:50:00Z" to 5, "2026-11-01T15:40:00Z" to 7)
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z", mint = 5)
        service.create(request())

        backfillDiscovery(lookup, properties, clock, usage).scan()

        val kept = sessions.findById("live-1").orElseThrow()
        val split = sessions.findAll().single { it.id != "live-1" && it.backfillId == null }
        assertEquals(AutomationSessionState.OPEN, kept.state)
        assertEquals(Instant.parse("2026-11-01T14:00:00Z"), kept.lastBuyAt)
        assertEquals("item-800", kept.firstItemId)
        assertEquals(AutomationSessionState.OPEN, split.state)
        assertEquals(Instant.parse("2026-11-01T15:40:00Z"), split.firstBuyAt)
        assertEquals(split.id, items.findById("item-802").orElseThrow().sessionId)
        assertEquals("item-800", items.findById("item-800").orElseThrow().id)
    }

    @Test
    fun `an earlier buy that replaces an item of a closed live session only recomputes its span and first item, and never reopens it`() {
        live()
        openLiveSession("2026-11-01T14:00:00Z" to 5, "2026-11-01T14:50:00Z" to 6, "2026-11-01T15:40:00Z" to 7)
        sessions.save(sessions.findById("live-1").orElseThrow().also { it.state = AutomationSessionState.CLOSED; it.closeReason = SessionCloseReason.IDLE; it.closedAt = EVENING })
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z", mint = 5)
        service.create(request())

        backfillDiscovery(lookup, properties, clock, usage).scan()

        val rebuilt = sessions.findById("live-1").orElseThrow()
        assertEquals(AutomationSessionState.CLOSED, rebuilt.state)
        assertEquals(SessionCloseReason.IDLE, rebuilt.closeReason)
        assertEquals("item-801", rebuilt.firstItemId)
        assertEquals(Instant.parse("2026-11-01T14:50:00Z"), rebuilt.firstBuyAt)
        assertEquals(Instant.parse("2026-11-01T15:40:00Z"), rebuilt.lastBuyAt)
        assertEquals(1, sessions.findAll().count { it.backfillId == null }, "no session was split off a closed one")
    }

    @Test
    fun `the scan writer only runs inside its caller's transaction`() {
        assertFailsWith<IllegalTransactionStateException> { scanWriter.isUntimed(item(1, "s")) }
    }

    @Test
    fun `a scan records how far it has read, the second after the oldest listed transaction, and the range start once it ends`() {
        live()
        val limited = properties(captureEnabled = true, maxDiscoveryPages = 1)
        lookup.actions += (1..45).map { buyAt(it, Instant.parse("2026-10-30T14:00:00Z").plusSeconds(60L * it)) }
        val created = service.create(request()).backfill
        val scanner = backfillDiscovery(lookup, limited, clock, usage)

        scanner.scan()
        val afterFirst = stored(created.id).scannedTo
        scanner.scan()
        val afterSecond = stored(created.id).scannedTo
        scanner.scan()

        assertEquals(Instant.parse("2026-10-30T14:26:01Z"), afterFirst, "buy 26 at 14:26:00 is the oldest of the first page; a transaction of its second not yet read may exist")
        assertEquals(Instant.parse("2026-10-30T14:06:01Z"), afterSecond)
        assertEquals(rangeStart, stored(created.id).scannedTo)
    }

    @Test
    fun `a scan stopped by its budget counts the part it read as covered, and live coverage stays where it was`() {
        live()
        lookup.actions += (1..120).map { buyAt(it, Instant.parse("2026-10-30T10:00:00Z").plusSeconds(60L * it)) }
        lookup.pageRequests = 400
        val created = service.create(request(budget = 1222)).backfill

        backfillDiscovery(lookup, properties, clock, usage).scan()

        val reached = Instant.parse("2026-10-30T10:41:01Z")
        assertEquals(reached, stored(created.id).scannedTo)
        assertEquals(coveredFrom, watermarks.findById(WALLET).orElseThrow().coveredFrom, "covered_from is the live floor, a stopped backfill never moves it")
        assertEquals(listOf(reached to EVENING.minusSeconds(60)), coverage.of(WALLET).ranges.map { it.from to it.to }, "the part it read joins live coverage in one range")
    }

    @Test
    fun `cancelling a backfill that was cancelled before it read anything does not move live coverage`() {
        live()
        val created = service.create(request()).backfill

        service.cancel(created.id, "cancel-1")

        assertEquals(coveredFrom, watermarks.findById(WALLET).orElseThrow().coveredFrom)
        assertEquals(listOf(coveredFrom), coverage.of(WALLET).ranges.map { it.from })
    }

    @Test
    fun `a retry over a range a stopped backfill partly covered re-reads all of it, takes over the stopped backfill's items, and finishes without counting the range twice`() {
        live()
        lookup.actions += (1..120).map { buyAt(it, Instant.parse("2026-10-30T10:00:00Z").plusSeconds(60L * it)) }
        lookup.pageRequests = 400
        val stopped = service.create(request(budget = 1222)).backfill
        backfillDiscovery(lookup, properties, clock, usage).scan()
        lookup.pageRequests = null

        val retry = service.create(request(requestId = "req-2")).backfill
        repeat(2) { backfillDiscovery(lookup, properties, clock, usage).scan() }

        assertEquals(TimeRange(rangeStart, coveredFrom), TimeRange(retry.rangeStart, retry.rangeEnd), "what a stopped backfill covered does not shorten the retry")
        assertEquals(BackfillState.CAPTURING, stored(retry.id).state)
        assertEquals(120, items.count(), "every buy is one item, whichever backfill found it")
        assertTrue(items.findAll().all { it.backfillId == retry.id }, "the stopped backfill's untouched items became the retry's")
        assertTrue(sessions.findAll().none { it.backfillId == stopped.id }, "and its sessions are gone with them")
        items.findAll().forEach { it.state = AutomationItemState.SIMULATED; items.save(it) }
        service.settle()
        assertEquals(BackfillState.DONE, stored(retry.id).state)
        assertEquals(rangeStart, watermarks.findById(WALLET).orElseThrow().coveredFrom)
        assertEquals(listOf(rangeStart to EVENING.minusSeconds(60)), coverage.of(WALLET).ranges.map { it.from to it.to }, "one range, counted once")
    }

    @Test
    fun `a cancelled backfill's range can be run again over the same dates`() {
        live()
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z")
        val first = service.create(request()).backfill
        backfillDiscovery(lookup, properties, clock, usage).scan()
        service.cancel(first.id, "cancel-1")

        val retry = service.create(request(requestId = "req-2")).backfill
        backfillDiscovery(lookup, properties, clock, usage).scan()

        assertEquals(TimeRange(rangeStart, coveredFrom), TimeRange(retry.rangeStart, retry.rangeEnd))
        val item = items.findAll().single()
        assertEquals(retry.id, item.backfillId, "the abandoned item is the retry's now")
        assertEquals(AutomationItemState.DISCOVERED, item.state)
    }

    @Test
    fun `a filtered backfill still captures its buys, but its range never counts as coverage and never moves live coverage`() {
        live()
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z", mint = 1)
        lookup.actions += buyAt(2, "2026-10-30T14:01:00Z", mint = 2)
        val created = service.create(request(mints = listOf(mint(1).value))).backfill
        backfillDiscovery(lookup, properties, clock, usage).scan()
        items.findAll().forEach { it.state = AutomationItemState.SIMULATED; items.save(it) }

        service.settle()

        assertEquals(true, created.filtered)
        assertEquals(BackfillState.DONE, stored(created.id).state)
        assertEquals(1, items.count())
        assertEquals(coveredFrom, watermarks.findById(WALLET).orElseThrow().coveredFrom)
        assertEquals(listOf(coveredFrom), coverage.of(WALLET).ranges.map { it.from }, "its days stay pending until an unfiltered backfill or live coverage covers them")
    }

    @Test
    fun `a minimum signal above the capture minimum filters a backfill, one equal to it does not`() {
        live()

        val above = service.create(request(minSignalSol = BigDecimal("2.5"))).backfill
        end(above.id, BackfillState.CANCELLED)
        val equal = service.create(request(requestId = "req-2", minSignalSol = BigDecimal("1.5"))).backfill

        assertEquals(true, above.filtered)
        assertEquals(false, equal.filtered)
    }

    @Test
    fun `a filtered done backfill blocks no one, an unfiltered done one blocks every new capturing backfill`() {
        live()
        val filtered = service.create(request(mints = listOf(mint(1).value))).backfill
        end(filtered.id, BackfillState.DONE)

        val unfilteredOverIt = service.create(request(requestId = "req-2")).backfill
        end(unfilteredOverIt.id, BackfillState.DONE)
        val refused = assertFailsWith<BackfillOverlapsCoverageException> { service.create(request(requestId = "req-3", mints = listOf(mint(2).value))) }

        assertEquals(BackfillState.QUEUED, unfilteredOverIt.state)
        assertEquals(unfilteredOverIt.id, refused.overlappingId)
    }

    @Test
    fun `a filtered backfill may overlap another filtered one that is done`() {
        live()
        val first = service.create(request(mints = listOf(mint(1).value))).backfill
        end(first.id, BackfillState.DONE)

        val second = service.create(request(requestId = "req-2", mints = listOf(mint(2).value))).backfill

        assertEquals(BackfillState.QUEUED, second.state)
    }

    @Test
    fun `an unfiltered backfill takes over the untouched and budget-failed items of a filtered one, and leaves what it simulated`() {
        live()
        backfills.saveAndFlush(AutomationBackfillEntity("old", "req-old", WALLET, null, LocalDate.parse("2026-10-29"), LocalDate.parse("2026-10-30"),
            rangeStart, coveredFrom, false, null, """["m"]""", 5000, BackfillState.DONE, EVENING, EVENING).also { it.filtered = true })
        val night = Instant.parse("2026-10-30T14:00:00Z")
        sessions.save(session("old-night", AutomationSessionState.CLOSED, night).also { it.backfillId = "old"; it.lastBuyAt = night.plusSeconds(300) })
        (1..3).forEach { n ->
            items.save(item(n, "old-night", blockTime = night.plusSeconds(60L * n), mint = n, signature = n).also { it.slot = 1000L + n; it.backfillId = "old" })
        }
        items.findById("item-2").orElseThrow().also { it.state = AutomationItemState.FAILED; it.failureCode = AutomationFailures.BACKFILL_BUDGET; it.step = AutomationStep.WALLET; items.save(it) }
        items.findById("item-3").orElseThrow().also { it.state = AutomationItemState.SIMULATED; it.step = AutomationStep.SWEEP; items.save(it) }
        lookup.actions += (1..3).map { buy(it, 1000L + it, night.plusSeconds(60L * it), mint = it) }
        val unfiltered = service.create(request(requestId = "req-2")).backfill

        backfillDiscovery(lookup, properties, clock, usage).scan()

        val byMint = items.findAll().associateBy { it.mint }
        assertEquals(listOf(unfiltered.id, unfiltered.id, "old"), (1..3).map { byMint.getValue(mint(it).value).backfillId })
    }

    @Test
    fun `cancelling a backfill abandons its non-terminal items whatever state their session is in, closed sessions included`() {
        live()
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z")
        val created = service.create(request()).backfill
        backfillDiscovery(lookup, properties, clock, usage).scan()
        sessions.findAll().forEach { it.state = AutomationSessionState.CLOSED; it.closeReason = SessionCloseReason.IDLE; sessions.save(it) }

        service.cancel(created.id, "cancel-1")

        assertEquals(BackfillState.CANCELLED, stored(created.id).state)
        assertEquals(AutomationItemState.ABANDONED, items.findAll().single().state)
        assertEquals(SessionCloseReason.IDLE, sessions.findAll().single().closeReason, "a closed session stays as it was closed")
    }

    @Test
    fun `a buy found again takes over the untouched, abandoned and budget-failed items of an earlier backfill, and leaves a simulated one and an abandoned one with its dataset`() {
        live()
        backfills.saveAndFlush(AutomationBackfillEntity("old", "req-old", WALLET, null, LocalDate.parse("2026-10-29"), LocalDate.parse("2026-10-30"),
            rangeStart, coveredFrom, false, null, null, 5000, BackfillState.STOPPED_BUDGET, EVENING, EVENING))
        val night = Instant.parse("2026-10-30T14:00:00Z")
        sessions.save(session("old-night", AutomationSessionState.CLOSING, night).also { it.backfillId = "old"; it.lastBuyAt = night.plusSeconds(600) })
        listOf(1, 2, 3, 4, 5).forEach { n ->
            items.save(item(n, "old-night", blockTime = night.plusSeconds(60L * n), mint = n, signature = n).also { it.slot = 1000L + n; it.backfillId = "old" })
        }
        items.findById("item-2").orElseThrow().also { it.state = AutomationItemState.FAILED; it.failureCode = AutomationFailures.BACKFILL_BUDGET; it.step = AutomationStep.WALLET; items.save(it) }
        items.findById("item-3").orElseThrow().also { it.state = AutomationItemState.ABANDONED; it.step = AutomationStep.MARKET; items.save(it) }
        items.findById("item-4").orElseThrow().also { it.state = AutomationItemState.SIMULATED; it.step = AutomationStep.SWEEP; items.save(it) }
        items.findById("item-5").orElseThrow().also { it.state = AutomationItemState.ABANDONED; it.step = AutomationStep.SWEEP; it.datasetId = "dataset"; items.save(it) }
        lookup.actions += (1..5).map { buy(it, 1000L + it, night.plusSeconds(60L * it), mint = it) }
        val retry = service.create(request(requestId = "req-2")).backfill

        backfillDiscovery(lookup, properties, clock, usage).scan()

        val byMint = items.findAll().associateBy { it.mint }
        assertEquals(5, items.count())
        listOf(1, 2, 3).forEach { n ->
            assertEquals(retry.id, byMint.getValue(mint(n).value).backfillId, "mint $n is the retry's now")
            assertEquals(AutomationItemState.DISCOVERED, byMint.getValue(mint(n).value).state)
            assertNull(byMint.getValue(mint(n).value).step)
        }
        assertEquals(listOf("old", "old"), listOf(4, 5).map { byMint.getValue(mint(it).value).backfillId })
        assertEquals(AutomationItemState.SIMULATED, byMint.getValue(mint(4).value).state)
        assertEquals(AutomationItemState.ABANDONED, byMint.getValue(mint(5).value).state)
        assertEquals(setOf("item-4", "item-5"), items.findAll().filter { it.sessionId == "old-night" }.map { it.id }.toSet(), "the old session keeps what it still holds")
        assertEquals(3, stored(retry.id).itemsCreated)
        val oldSession = sessions.findById("old-night").orElseThrow()
        assertEquals(night.plusSeconds(240), oldSession.firstBuyAt)
        assertEquals(night.plusSeconds(300), oldSession.lastBuyAt)
        assertEquals("item-4", oldSession.firstItemId)
    }

    @Test
    fun `cancelling a stopped backfill whose items still wait abandons them and closes their sessions, and a stopped one with nothing left to do is returned as it is`() {
        live()
        lookup.actions += (1..120).map { buyAt(it, Instant.parse("2026-10-30T10:00:00Z").plusSeconds(60L * it)) }
        lookup.pageRequests = 400
        val created = service.create(request(budget = 1222)).backfill
        backfillDiscovery(lookup, properties, clock, usage).scan()
        clock.now = EVENING.plusSeconds(30)

        val cancelled = service.cancel(created.id, "cancel-1").backfill
        val again = service.cancel(created.id, "cancel-2").backfill

        assertEquals(BackfillState.CANCELLED, cancelled.state)
        assertEquals(clock.now, cancelled.finishedAt)
        assertTrue(items.findAll().all { it.state == AutomationItemState.ABANDONED })
        assertTrue(sessions.findAll().all { it.state == AutomationSessionState.CLOSED && it.closeReason == SessionCloseReason.MANUAL })
        assertEquals(cancelled, again)

        val done = service.create(request(requestId = "req-2", from = "2026-10-20", to = "2026-10-21", dryRun = true)).backfill
        end(done.id, BackfillState.STOPPED_BUDGET)
        assertEquals(BackfillState.STOPPED_BUDGET, service.cancel(done.id, "cancel-3").backfill.state)
    }

    @Test
    fun `pages read before the provider failed mid-tick are kept and charged, the scan continues at the failing page and each failure is logged with its count`() {
        live()
        val limited = properties(captureEnabled = true, maxDiscoveryPages = 2)
        lookup.actions += (1..120).map { buyAt(it, Instant.parse("2026-10-30T10:00:00Z").plusSeconds(60L * it)) }
        val created = service.create(request()).backfill
        val scanner = backfillDiscovery(lookup, limited, clock, usage)
        val warnings = ListAppender<ILoggingEvent>().also { it.start() }
        val logger = LoggerFactory.getLogger(AutomationBackfillDiscoveryService::class.java) as Logger
        logger.addAppender(warnings)

        try {
            lookup.unavailableFromRequest = 2
            val partial = scanner.scan()
            val afterPartial = stored(created.id)
            lookup.unavailableFromRequest = null
            val resumed = scanner.scan()
            val afterResumed = stored(created.id)
            lookup.unavailableFromRequest = 5
            val failedAtOnce = scanner.scan()

            assertTrue(partial is BackfillScanOutcome.Recorded)
            assertEquals(BackfillState.SCANNING, afterPartial.state)
            assertEquals(20, afterPartial.itemsCreated, "the page read before the failure")
            assertEquals(20, afterPartial.discoveryRequests, "and its requests")
            assertEquals(signature(101).value, afterPartial.resumeBefore, "the failing page is read again next time")
            assertTrue(resumed is BackfillScanOutcome.Recorded)
            assertEquals(60, afterResumed.itemsCreated, "the tick after read the failed page and the one behind it")
            assertEquals(BackfillScanOutcome.Unavailable(WalletActivityUnavailable.PROVIDER_RATE_LIMITED), failedAtOnce)
            assertEquals(60, stored(created.id).itemsCreated, "a first page that fails stores nothing")
            val counts = warnings.list.filter { it.level == Level.WARN }.mapNotNull { Regex("consecutiveFailures=(\\d+)").find(it.formattedMessage)?.groupValues?.get(1) }
            assertEquals(listOf("1", "1"), counts, "the tick that read its pages in between reset the count")
        } finally {
            logger.detachAppender(warnings)
        }
    }

    @Test
    fun `actions without a block time are counted on the backfill, not only logged`() {
        live()
        lookup.actions += buyAt(1, "2026-10-30T14:00:00Z")
        lookup.actions += buy(2, 4_000_000_000L, null)
        lookup.actions += buy(3, 4_000_000_001L, null, mint = 3)
        val created = service.create(request()).backfill

        backfillDiscovery(lookup, properties, clock, usage).scan()

        assertEquals(2, query.get(created.id).backfill.skippedNoBlockTime)
        assertEquals(1, items.count())
    }

    private fun live(coveredFrom: Instant? = this.coveredFrom) {
        watermarks.saveAndFlush(AutomationWatermarkEntity(WALLET, 1, "sig-0", 2, -1, EVENING).also { it.coveredFrom = coveredFrom })
    }

    private fun request(
        requestId: String = "req-1",
        from: String = "2026-10-29",
        to: String = "2026-10-30",
        dryRun: Boolean = false,
        budget: Long = 5000,
        minSignalSol: BigDecimal? = null,
        mints: List<String>? = null,
    ) = BackfillRequest(requestId, LocalDate.parse(from), LocalDate.parse(to), dryRun, budget, minSignalSol, mints)

    private fun buyAt(n: Int, time: String, lamports: Long = CAPTURED_LAMPORTS, mint: Int = n): WalletAction = buyAt(n, Instant.parse(time), lamports, mint)

    private fun buyAt(n: Int, time: Instant, lamports: Long = CAPTURED_LAMPORTS, mint: Int = n): WalletAction = buy(n, time.epochSecond, time, lamports, mint)

    private fun captured(n: Int, requests: Int) =
        item(n, "captured-$n", AutomationItemState.SIMULATED, blockTime = Instant.parse("2026-11-01T14:00:00Z"), mint = 500 + n, signature = 500 + n)
            .also { it.requestsUsed = requests }

    private fun stored(id: String): AutomationBackfillEntity = backfills.findById(id).orElseThrow()

    /** The live session `live-1` with one discovered item per ([time], mint) pair, items numbered 800 on; later than any buy a backfill finds. */
    private fun openLiveSession(vararg buys: Pair<String, Int>) {
        val times = buys.map { Instant.parse(it.first) }
        sessions.save(session("live-1", AutomationSessionState.OPEN, times.first(), firstItemId = "item-800").also { it.lastBuyAt = times.last() })
        buys.forEachIndexed { index, (time, mint) ->
            items.save(item(800 + index, "live-1", blockTime = Instant.parse(time), mint = mint, signature = 700 + index).also { it.slot = LIVE_SLOT + index })
        }
    }

    private fun end(id: String, state: BackfillState) {
        backfills.saveAndFlush(stored(id).also { it.state = state; it.activeWallet = null })
    }

    private fun setItems(state: AutomationItemState) {
        items.findAll().forEach { it.state = state; items.save(it) }
    }

    private fun backfill(id: String, from: String, to: String, state: BackfillState): AutomationBackfillEntity =
        backfills.saveAndFlush(AutomationBackfillEntity(id, "req-$id", WALLET, null, LocalDate.parse(from.take(10)), LocalDate.parse(to.take(10)),
            Instant.parse(from), Instant.parse(to), false, null, null, 1000, state, EVENING, EVENING))

    private companion object {
        /** Above every slot a test buy has, so a live item is later than any buy a backfill finds. */
        const val LIVE_SLOT = 9_999_999_999L
    }
}
