package com.trenching.bot.console.automation

import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationFixtures.SKIPPED_LAMPORTS
import com.trenching.bot.console.automation.AutomationFixtures.WALLET
import com.trenching.bot.console.automation.AutomationFixtures.buy
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.AutomationFixtures.sell
import com.trenching.bot.console.automation.AutomationFixtures.signature
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.automation.domain.DiscoveryCursor
import com.trenching.bot.console.automation.domain.DiscoveryOutcome
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkEntity
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AutomationDiscoveryServiceTest : AutomationDatabaseTest() {
    private val clock = MutableClock(EVENING)
    private val lookup = FakeWalletActivityLookup()

    @Test
    fun `reads pages of twenty newest first, attributed to wallet activity`() {
        lookup.actions += (1..45).map { buy(it, 1000L + it, EVENING.minusSeconds(100L - it), mint = it) }

        discovery(lookup, properties(), clock).discover()

        assertEquals(listOf(null, signature(26), signature(6)), lookup.requests.map { it.first.before })
        assertTrue(lookup.requests.all { it.first.limit == 20 && it.first.wallet.value == WALLET })
        assertTrue(lookup.requests.all { it.second == ApiUsageContext(ApiTask.WALLET_ACTIVITY, null) })
        assertEquals(45, items.count())
    }

    @Test
    fun `the fee-margin filter captures one lamport above its boundary and counts the buy below it on the session`() {
        lookup.actions += buy(1, 100, EVENING.minusSeconds(600), lamports = 1_456_310_681)
        lookup.actions += buy(2, 101, EVENING.minusSeconds(590), lamports = 1_456_310_680)

        discovery(lookup, properties(), clock).discover()

        assertEquals(listOf(signature(1).value), items.findAll().map { it.signature })
        assertEquals(BigInteger.valueOf(1_456_310_681), items.findAll().single().curvePaymentLamports)
        assertEquals(1, sessions.findAll().single().belowFilterCount)
    }

    @Test
    fun `sells and later buys of a mint already discovered create no item`() {
        lookup.actions += buy(1, 100, EVENING.minusSeconds(600), mint = 7)
        lookup.actions += sell(2, 101, EVENING.minusSeconds(590), mint = 7)
        lookup.actions += buy(3, 102, EVENING.minusSeconds(580), mint = 7)
        val service = discovery(lookup, properties(), clock)

        service.discover()
        lookup.actions += buy(4, 103, EVENING.plusSeconds(10), mint = 7)
        clock.now = EVENING.plusSeconds(60)
        service.discover()

        assertEquals(listOf(signature(1).value), items.findAll().map { it.signature })
        assertEquals(0, sessions.findAll().single().belowFilterCount)
    }

    @Test
    fun `a complete scan advances the watermark to the newest action and the next scan stops paging at it`() {
        lookup.actions += (1..30).map { buy(it, 1000L + it, EVENING.minusSeconds(100L - it)) }
        val service = discovery(lookup, properties(), clock)
        service.discover()
        lookup.requests.clear()

        lookup.actions += buy(31, 1031, EVENING.plusSeconds(30))
        clock.now = EVENING.plusSeconds(600)
        service.discover()

        assertEquals(listOf(null), lookup.requests.map { it.first.before }, "the first page already reaches the watermark")
        assertEquals(DiscoveryCursor(1031, signature(31).value, 2, DiscoveryCursor.TOP_LEVEL_INNER_INDEX), watermark())
        assertEquals(31, items.count())
    }

    @Test
    fun `a scan cut short by the page limit stores what it read, keeps the watermark and stores where the next scan resumes`() {
        lookup.actions += buy(1, 1000, EVENING.minusSeconds(100))
        discovery(lookup, properties(), clock).discover()
        lookup.actions += (2..31).map { buy(it, 1000L + it, EVENING.minusSeconds(100L - it)) }
        lookup.requests.clear()

        val outcome = discovery(lookup, properties(maxDiscoveryPages = 1), clock).discover()

        assertEquals(1, lookup.requests.size)
        assertEquals(21, items.count())
        assertEquals(cursor(1, 1000), watermark())
        assertEquals(false, (outcome as DiscoveryOutcome.Recorded).record.watermarkAdvanced)
        val stored = records.toDiscoveryWatermark(watermarks.findById(WALLET).orElseThrow())
        assertEquals(signature(12).value, stored.resumeBefore)
        assertEquals(cursor(31, 1031), stored.pending)
        assertNull(stored.resumeSince)
    }

    @Test
    fun `the next scan continues from the cursor and, at the watermark, advances it to the newest action of the whole catch-up`() {
        lookup.actions += buy(1, 1000, EVENING.minusSeconds(100))
        discovery(lookup, properties(), clock).discover()
        lookup.actions += (2..31).map { buy(it, 1000L + it, EVENING.minusSeconds(100L - it)) }
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)
        capped.discover()
        lookup.actions += buy(32, 1100, EVENING.plusSeconds(30))
        clock.now = EVENING.plusSeconds(600)
        lookup.requests.clear()

        val outcome = capped.discover()
        val caughtUp = records.toDiscoveryWatermark(watermarks.findById(WALLET).orElseThrow())
        capped.discover()

        assertEquals(signature(12), lookup.requests.first().first.before)
        assertEquals(true, (outcome as DiscoveryOutcome.Recorded).record.watermarkAdvanced)
        assertEquals(cursor(31, 1031), caughtUp.cursor)
        assertNull(caughtUp.resumeBefore)
        assertNull(caughtUp.pending)
        assertEquals(cursor(32, 1100), watermark())
        assertEquals((1..32).map { signature(it).value }.toSet(), items.findAll().map { it.signature }.toSet())
    }

    @Test
    fun `a catch-up completed by a later scan moves discovery up to the start of the scan that began it, so steady activity cannot freeze it`() {
        lookup.actions += buy(1, 1000, EVENING.minusSeconds(100))
        discovery(lookup, properties(), clock).discover()
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)
        var n = 1
        val through = mutableListOf<Instant?>()

        listOf(30, 15, 15, 15).forEachIndexed { tick, trades ->
            clock.now = EVENING.plus(Duration.ofMinutes(10L * (tick + 1)))
            repeat(trades) { lookup.actions += buy(++n, 1000L + n, clock.now.minusSeconds(200L - it)) }
            capped.discover()
            through += records.toDiscoveryWatermark(watermarks.findById(WALLET).orElseThrow()).discoveredThrough
        }

        val atScanStart = { minutes: Long -> EVENING.plus(Duration.ofMinutes(minutes)).minusSeconds(60) }
        assertEquals(listOf(atScanStart(0), atScanStart(10), atScanStart(10), atScanStart(30)), through)
        assertEquals(n - 15, items.count().toInt(), "the last tick completed the catch-up; its own trades wait for the next scan")
    }

    @Test
    fun `a first scan cut short keeps its initial lookback as the bound its catch-up reaches`() {
        lookup.actions += buy(40, 998, EVENING.minus(Duration.ofHours(12)).minusSeconds(1))
        lookup.actions += buy(1, 999, EVENING.minus(Duration.ofHours(12)).plusSeconds(300))
        lookup.actions += (2..31).map { buy(it, 1000L + it, EVENING.minusSeconds(100L - it)) }
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)

        capped.discover()
        val started = records.toDiscoveryWatermark(watermarks.findById(WALLET).orElseThrow())
        clock.now = EVENING.plusSeconds(600)
        capped.discover()

        assertEquals(cursor(31, 1031), started.cursor)
        assertEquals(EVENING.minus(Duration.ofHours(12)), started.resumeSince)
        assertEquals(signature(12).value, started.resumeBefore)
        assertEquals((1..31).map { signature(it).value }.toSet(), items.findAll().map { it.signature }.toSet())
        val finished = records.toDiscoveryWatermark(watermarks.findById(WALLET).orElseThrow())
        assertEquals(cursor(31, 1031), finished.cursor)
        assertNull(finished.resumeSince)
        assertNull(finished.resumeBefore)
    }

    @Test
    fun `a range delivered again inserts each buy once`() {
        lookup.actions += (1..30).map { buy(it, 1000L + it, EVENING.minusSeconds(100L - it)) }
        lookup.pageOrder = { page -> page + page.take(2) }
        val service = discovery(lookup, properties(maxDiscoveryPages = 1), clock)

        repeat(3) { service.discover() }

        assertEquals(30, items.count())
        assertEquals((1..30).map { signature(it).value }.toSet(), items.findAll().map { it.signature }.toSet())
    }

    @Test
    fun `a page out of order is processed oldest first`() {
        lookup.actions += buy(1, 100, EVENING.minusSeconds(300), inner = 4)
        lookup.actions += buy(2, 100, EVENING.minusSeconds(300), mint = 2)
        lookup.actions += buy(3, 101, EVENING.minusSeconds(200))
        lookup.pageOrder = { page -> page.reversed().shuffled(java.util.Random(7)) }

        discovery(lookup, properties(), clock).discover()

        val first = listOf(buy(1, 100, null, inner = 4), buy(2, 100, null, mint = 2)).minOf(DiscoveryCursor::of)
        val firstItemId = AutomationIds.item(WALLET, first)
        val session = sessions.findAll().single()
        assertEquals(firstItemId, session.firstItemId)
        assertEquals(AutomationIds.session(WALLET, firstItemId), session.id)
        assertEquals(DiscoveryCursor(101, signature(3).value, 2, -1), watermark())
    }

    @Test
    fun `item ids hash the wallet, signature, instruction and inner index, with -1 for a top-level event`() {
        lookup.actions += buy(1, 100, EVENING.minusSeconds(300))
        lookup.actions += buy(2, 101, EVENING.minusSeconds(200), inner = 3)

        discovery(lookup, properties(), clock).discover()

        val expected = setOf(
            sha256("$WALLET|${signature(1).value}|2|-1"),
            sha256("$WALLET|${signature(2).value}|2|3"),
        )
        assertEquals(expected, items.findAll().map { it.id }.toSet())
        assertTrue(items.findAll().all { it.id.length == 64 })
    }

    @Test
    fun `a buy without a block time is stored failed and never scheduled`() {
        lookup.actions += buy(1, 100, EVENING.minusSeconds(300))
        lookup.actions += buy(2, 101, null)

        discovery(lookup, properties(), clock).discover()

        val failed = item(signature(2).value)
        assertEquals(AutomationItemState.FAILED, failed.state)
        assertEquals(AutomationFailures.MISSING_BLOCK_TIME, failed.failureCode)
        assertNull(failed.blockTime)
        assertNull(failed.nextActionAt)
        assertEquals(item(signature(1).value).sessionId, failed.sessionId)
        assertEquals(AutomationItemState.DISCOVERED, item(signature(1).value).state)
        assertEquals(EVENING, item(signature(1).value).nextActionAt)
        val session = sessions.findAll().single()
        assertEquals(listOf(EVENING.minusSeconds(300), EVENING.minusSeconds(300)), listOf(session.firstBuyAt, session.lastBuyAt), "its span is unchanged")
    }

    @Test
    fun `a buy without a block time and no open session gets a session of its own that takes no later buy`() {
        lookup.actions += buy(1, 100, null)
        discovery(lookup, properties(), clock).discover()
        lookup.actions += buy(2, 200, EVENING.plusSeconds(30))
        clock.now = EVENING.plusSeconds(600)

        discovery(lookup, properties(), clock).discover()

        val untimed = sessions.findOneById(item(signature(1).value).sessionId) ?: error("stored")
        assertEquals(AutomationSessionState.CLOSING, untimed.state)
        assertEquals(item(signature(1).value).id, untimed.firstItemId)
        assertEquals(listOf(EVENING, EVENING), listOf(untimed.firstBuyAt, untimed.lastBuyAt), "it starts at the discovery time")
        assertTrue(item(signature(2).value).sessionId != untimed.id)
        assertEquals(AutomationSessionState.OPEN, sessions.findOneById(item(signature(2).value).sessionId)?.state)
    }

    @Test
    fun `a buy exactly the idle time after the last one joins its session`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofMinutes(90)))
        lookup.actions += buy(2, 200, EVENING.minus(Duration.ofMinutes(30)))

        discovery(lookup, properties(), clock).discover()

        val session = sessions.findAll().single()
        assertEquals(EVENING.minus(Duration.ofMinutes(90)), session.firstBuyAt)
        assertEquals(EVENING.minus(Duration.ofMinutes(30)), session.lastBuyAt)
        assertEquals(AutomationSessionState.OPEN, session.state)
    }

    @Test
    fun `a buy one millisecond later opens a new session, and discovery leaves closing the earlier one to the tick`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofMinutes(90)))
        lookup.actions += buy(2, 200, EVENING.minus(Duration.ofMinutes(30)).plusMillis(1))

        discovery(lookup, properties(), clock).discover()
        val discovered = sessions.findAll().sortedBy(AutomationSessionEntity::firstBuyAt).map { it.state }
        sessionService(properties(), clock).closeIdleSessions()

        val byFirstBuy = sessions.findAll().sortedBy(AutomationSessionEntity::firstBuyAt)
        assertEquals(listOf(AutomationSessionState.OPEN, AutomationSessionState.OPEN), discovered)
        assertEquals(listOf(AutomationSessionState.CLOSED, AutomationSessionState.OPEN), byFirstBuy.map { it.state }, "phase 0 closes at once")
        assertEquals(listOf(item(signature(1).value).sessionId, item(signature(2).value).sessionId), byFirstBuy.map { it.id })
    }

    @Test
    fun `a catch-up buy older than the idle time before the open session lands in its own earlier session`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofHours(3)))
        lookup.actions += (2..21).map { buy(it, 200L + it, EVENING.minus(Duration.ofMinutes(30)).plusSeconds(it.toLong())) }
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)

        capped.discover()
        clock.now = EVENING.plusSeconds(600)
        capped.discover()

        val earlier = item(signature(1).value)
        val later = item(signature(2).value)
        assertEquals(2, sessions.count())
        assertTrue(earlier.sessionId != later.sessionId)
        val own = sessions.findOneById(earlier.sessionId) ?: error("stored")
        assertEquals(listOf(EVENING.minus(Duration.ofHours(3)), EVENING.minus(Duration.ofHours(3))), listOf(own.firstBuyAt, own.lastBuyAt))
        assertEquals(AutomationIds.session(WALLET, earlier.id), own.id)
        assertEquals(EVENING.minus(Duration.ofMinutes(30)).plusSeconds(2), sessions.findOneById(later.sessionId)?.firstBuyAt)
        assertTrue(items.findAll().filter { it.signature != signature(1).value }.all { it.sessionId == later.sessionId })
    }

    @Test
    fun `a catch-up buy within the idle time before the open session joins it, which keeps the id of its first discovered buy`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofMinutes(90)).plusSeconds(2))
        lookup.actions += (2..21).map { buy(it, 200L + it, EVENING.minus(Duration.ofMinutes(30)).plusSeconds(it.toLong())) }
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)

        capped.discover()
        clock.now = EVENING.plusSeconds(600)
        capped.discover()

        val session = sessions.findAll().single()
        assertEquals(EVENING.minus(Duration.ofMinutes(90)).plusSeconds(2), session.firstBuyAt)
        assertEquals(item(signature(2).value).id, session.firstItemId)
        assertEquals(AutomationIds.session(WALLET, item(signature(2).value).id), session.id)
        assertEquals(21, items.findAll().count { it.sessionId == session.id })
    }

    @Test
    fun `a catch-up buy that bridges two open sessions merges them into the one discovered first`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofMinutes(5)))
        discovery(lookup, properties(), clock).discover()
        val first = item(signature(1).value).sessionId
        lookup.actions += buy(2, 200, EVENING.plus(Duration.ofMinutes(50)))
        lookup.actions += (3..22).map { buy(it, 300L + it, EVENING.plus(Duration.ofMinutes(105)).plusSeconds(it.toLong())) }
        clock.now = EVENING.plus(Duration.ofHours(2))
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)

        capped.discover()
        val beforeBridge = sessions.count()
        capped.discover()

        assertEquals(2, beforeBridge)
        val merged = sessions.findAll().single()
        assertEquals(first, merged.id)
        assertEquals(EVENING.minus(Duration.ofMinutes(5)), merged.firstBuyAt)
        assertEquals(EVENING.plus(Duration.ofMinutes(105)).plusSeconds(22), merged.lastBuyAt)
        assertTrue(items.findAll().all { it.sessionId == first })
        assertEquals(22, items.count())
    }

    @Test
    fun `an earlier buy of a mint found by a catch-up takes the item of its later buy while that one's capture has not started`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofMinutes(30)), mint = 7)
        lookup.actions += (2..21).map { buy(it, 200L + it, EVENING.minus(Duration.ofMinutes(20)).plusSeconds(it.toLong()), mint = if (it == 21) 7 else it + 100) }
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)

        capped.discover()
        val later = item(signature(21).value)
        capped.discover()

        val kept = items.findAll().single { it.mint == AutomationFixtures.mint(7).value }
        assertEquals(signature(1).value, kept.signature)
        assertEquals(AutomationItemState.DISCOVERED, kept.state)
        assertEquals(later.sessionId, kept.sessionId)
        assertNull(items.findOneById(later.id))
        assertEquals(20, items.count())
    }

    @Test
    fun `a catch-up buy that bridges two sessions opened in the same scan merges them into the one with the earlier first buy`() {
        lookup.actions += buy(1, 290, EVENING.minus(Duration.ofHours(3)))
        lookup.actions += buy(2, 300, EVENING.minus(Duration.ofHours(3)).plus(Duration.ofMinutes(100)))
        lookup.otherTransactions += (200..217).map { signature(it) to it.toLong() }
        lookup.actions += buy(3, 100, EVENING.minus(Duration.ofHours(3)).plus(Duration.ofMinutes(50)))
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)

        capped.discover()
        val earlier = item(signature(1).value).sessionId
        val later = item(signature(2).value).sessionId
        clock.now = EVENING.plusSeconds(600)
        capped.discover()

        assertTrue(earlier > later, "the earlier session's id orders after the later one's, so the id alone would keep the later")
        assertEquals(listOf(earlier), sessions.findAll().map { it.id })
        assertTrue(items.findAll().all { it.sessionId == earlier })
    }

    @Test
    fun `a merge moves the absorbed session's items and sweep rows and adds up the buys below the filter`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofMinutes(5)))
        lookup.actions += buy(30, 101, EVENING.minus(Duration.ofMinutes(4)), lamports = AutomationFixtures.SKIPPED_LAMPORTS)
        discovery(lookup, properties(), clock).discover()
        val first = item(signature(1).value).sessionId
        lookup.actions += buy(2, 200, EVENING.plus(Duration.ofMinutes(50)))
        lookup.actions += (3..21).map { buy(it, 300L + it, EVENING.plus(Duration.ofMinutes(105)).plusSeconds(it.toLong())) }
        lookup.actions += buy(31, 340, EVENING.plus(Duration.ofMinutes(106)), lamports = AutomationFixtures.SKIPPED_LAMPORTS)
        clock.now = EVENING.plus(Duration.ofHours(2))
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)
        capped.discover()
        val absorbed = item(signature(3).value).sessionId
        sweepRuns.save(AutomationRows.sweepRun("run-1", item(signature(3).value).id, "V00", sessionId = absorbed))
        sweepRuns.save(AutomationRows.sweepRun("run-2", item(signature(4).value).id, "V00", sessionId = absorbed))

        capped.discover()

        assertEquals(listOf(first), sessions.findAll().map { it.id })
        assertTrue(items.findAll().all { it.sessionId == first })
        assertEquals(listOf(first, first), sweepRuns.findAll().map { it.sessionId })
        assertEquals(2, sessions.findAll().single().belowFilterCount)
    }

    @Test
    fun `the same buys found oldest first or by a catch-up end in the same sessions`() {
        val base = EVENING.minus(Duration.ofHours(4))
        val buys = listOf(
            buy(1, 100, base.minus(Duration.ofMinutes(5)), mint = 7),
            buy(2, 110, base, mint = 8),
            buy(3, 200, base.plus(Duration.ofMinutes(50)), mint = 7),
            buy(4, 300, base.plus(Duration.ofMinutes(90)), mint = 9),
        )
        val fillers = (120..137).map { signature(it) to it.toLong() }

        val oldestFirst = sessionsOf(buys, fillers, maxDiscoveryPages = 5)
        emptyAutomationTables()
        val byCatchUp = sessionsOf(buys, fillers, maxDiscoveryPages = 1)

        val expected = setOf(
            Triple(setOf(signature(1).value, signature(2).value), base.minus(Duration.ofMinutes(5)), base),
            Triple(setOf(signature(4).value), base.plus(Duration.ofMinutes(90)), base.plus(Duration.ofMinutes(90))),
        )
        assertEquals(expected, oldestFirst)
        assertEquals(expected, byCatchUp)
    }

    @Test
    fun `a replaced item's session keeps its id and takes its span and first item from the items left`() {
        val base = EVENING.minus(Duration.ofHours(4))
        lookup.actions += buy(1, 100, base.minus(Duration.ofMinutes(5)), mint = 7)
        lookup.actions += buy(3, 200, base.plus(Duration.ofMinutes(50)), mint = 7)
        lookup.actions += buy(4, 300, base.plus(Duration.ofMinutes(90)), mint = 9)
        lookup.otherTransactions += (120..137).map { signature(it) to it.toLong() }
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)
        capped.discover()
        val replacedId = item(signature(3).value).id
        val sessionId = item(signature(3).value).sessionId

        clock.now = EVENING.plusSeconds(600)
        capped.discover()

        val kept = sessions.findOneById(sessionId) ?: error("kept")
        assertEquals(AutomationIds.session(WALLET, replacedId), kept.id)
        assertEquals(item(signature(4).value).id, kept.firstItemId)
        assertEquals(listOf(base.plus(Duration.ofMinutes(90)), base.plus(Duration.ofMinutes(90))), listOf(kept.firstBuyAt, kept.lastBuyAt))
        assertTrue(item(signature(1).value).sessionId != sessionId, "the earlier buy is too far before the buy left to join it")
    }

    @Test
    fun `a replaced item that was its session's only one leaves no empty session`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofHours(4)), mint = 7)
        lookup.actions += buy(3, 200, EVENING.minus(Duration.ofHours(2)), mint = 7)
        lookup.otherTransactions += (120..138).map { signature(it) to it.toLong() }
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)
        capped.discover()
        val emptied = item(signature(3).value).sessionId

        clock.now = EVENING.plusSeconds(600)
        capped.discover()

        assertNull(sessions.findOneById(emptied))
        assertEquals(listOf(item(signature(1).value).sessionId), sessions.findAll().map { it.id })
    }

    @Test
    fun `a replacement that leaves a gap longer than the idle time splits the session and moves the sweep rows of the part split off`() {
        val base = EVENING.minus(Duration.ofHours(4))
        lookup.actions += buy(1, 100, base.minus(Duration.ofMinutes(200)), mint = 7)
        lookup.actions += buy(2, 200, base, mint = 8)
        lookup.actions += buy(3, 210, base.plus(Duration.ofMinutes(45)), mint = 7)
        lookup.actions += buy(4, 220, base.plus(Duration.ofMinutes(90)), mint = 9)
        lookup.otherTransactions += (120..136).map { signature(it) to it.toLong() }
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)
        capped.discover()
        val original = item(signature(2).value).sessionId
        sweepRuns.save(AutomationRows.sweepRun("run-kept", item(signature(2).value).id, "V00", sessionId = original))
        sweepRuns.save(AutomationRows.sweepRun("run-split", item(signature(4).value).id, "V00", sessionId = original))

        clock.now = EVENING.plusSeconds(600)
        capped.discover()

        assertEquals(original, item(signature(2).value).sessionId, "the part holding the session's first item keeps it")
        val split = sessions.findOneById(item(signature(4).value).sessionId) ?: error("split")
        assertEquals(AutomationIds.session(WALLET, item(signature(4).value).id), split.id)
        assertEquals(listOf(base.plus(Duration.ofMinutes(90)), base.plus(Duration.ofMinutes(90))), listOf(split.firstBuyAt, split.lastBuyAt))
        assertEquals(listOf(base, base), sessions.findOneById(original)?.let { listOf(it.firstBuyAt, it.lastBuyAt) })
        assertEquals(3, sessions.count())
        assertEquals(mapOf("run-kept" to original, "run-split" to split.id), sweepRuns.findAll().associate { it.id to it.sessionId })
    }

    @Test
    fun `a split part that a buy of the same scan bridges back is merged into the session it was split from`() {
        val base = EVENING.minus(Duration.ofHours(4))
        lookup.actions += buy(1, 100, base.minus(Duration.ofMinutes(200)), mint = 7)
        lookup.actions += buy(5, 110, base.plus(Duration.ofMinutes(45)), mint = 10)
        lookup.actions += buy(2, 200, base, mint = 8)
        lookup.actions += buy(3, 210, base.plus(Duration.ofMinutes(45)), mint = 7)
        lookup.actions += buy(4, 220, base.plus(Duration.ofMinutes(90)), mint = 9)
        lookup.otherTransactions += (120..136).map { signature(it) to it.toLong() }
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)
        capped.discover()
        val original = item(signature(2).value).sessionId

        clock.now = EVENING.plusSeconds(600)
        capped.discover()

        val merged = sessions.findOneById(original) ?: error("kept")
        assertEquals(setOf(original, item(signature(1).value).sessionId), sessions.findAll().map { it.id }.toSet())
        assertEquals(listOf(original, original, original), listOf(2, 4, 5).map { item(signature(it).value).sessionId })
        assertEquals(listOf(base, base.plus(Duration.ofMinutes(90))), listOf(merged.firstBuyAt, merged.lastBuyAt))
        assertEquals(AutomationSessionState.OPEN, merged.state)
    }

    @Test
    fun `a session left with only buys without a block time starts closing and points its first item at the first one left`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofHours(3)), mint = 7)
        lookup.actions += buy(2, 200, EVENING.minus(Duration.ofMinutes(30)), mint = 7)
        lookup.actions += buy(3, 210, null, mint = 8)
        lookup.otherTransactions += (120..137).map { signature(it) to it.toLong() }
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)
        capped.discover()
        val sessionId = item(signature(2).value).sessionId

        clock.now = EVENING.plusSeconds(600)
        capped.discover()

        val closing = sessions.findOneById(sessionId) ?: error("kept")
        assertEquals(AutomationSessionState.CLOSING, closing.state)
        assertEquals(item(signature(3).value).id, closing.firstItemId)
        assertEquals(sessionId, item(signature(3).value).sessionId)
        assertTrue(item(signature(1).value).sessionId != sessionId)
        assertEquals(AutomationSessionState.OPEN, sessions.findOneById(item(signature(1).value).sessionId)?.state)
    }

    @Test
    fun `an earlier buy of a mint takes the item of a later buy without a block time, which had a closing session of its own`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofHours(3)), mint = 7)
        lookup.actions += buy(2, 200, null, mint = 7)
        lookup.otherTransactions += (120..138).map { signature(it) to it.toLong() }
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)
        capped.discover()
        val untimedSession = item(signature(2).value).sessionId

        clock.now = EVENING.plusSeconds(600)
        capped.discover()

        val kept = items.findAll().single()
        assertEquals(signature(1).value, kept.signature)
        assertEquals(AutomationItemState.DISCOVERED, kept.state)
        assertNull(sessions.findOneById(untimedSession), "the session left with no item is deleted")
        assertEquals(listOf(kept.sessionId), sessions.findAll().map { it.id })
        assertEquals(AutomationSessionState.OPEN, sessions.findAll().single().state)
    }

    @Test
    fun `an earlier buy of a mint leaves a later buy without a block time in a closed session as it is`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofHours(3)), mint = 7)
        lookup.actions += buy(2, 200, null, mint = 7)
        lookup.otherTransactions += (120..138).map { signature(it) to it.toLong() }
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)
        capped.discover()
        val untimed = item(signature(2).value)
        sessions.save((sessions.findOneById(untimed.sessionId) ?: error("stored")).apply { state = AutomationSessionState.CLOSED })

        clock.now = EVENING.plusSeconds(600)
        capped.discover()

        assertEquals(untimed.id, items.findAll().single { it.mint == AutomationFixtures.mint(7).value }.id)
        assertEquals(1, sessions.count())
    }

    @Test
    fun `an earlier buy of a mint takes the item of a later buy that failed for its missing block time`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofMinutes(30)), mint = 7)
        lookup.actions += (2..20).map { buy(it, 200L + it, EVENING.minus(Duration.ofMinutes(20)).plusSeconds(it.toLong()), mint = it + 100) }
        lookup.actions += buy(21, 230, null, mint = 7)
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)

        capped.discover()
        val failed = item(signature(21).value)
        capped.discover()

        val kept = items.findAll().single { it.mint == AutomationFixtures.mint(7).value }
        assertEquals(AutomationFailures.MISSING_BLOCK_TIME, failed.failureCode)
        assertEquals(signature(1).value, kept.signature)
        assertEquals(AutomationItemState.DISCOVERED, kept.state)
        assertNull(items.findOneById(failed.id))
    }

    @Test
    fun `an earlier buy of a mint found after the later buy's capture started leaves that item as it is`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofMinutes(30)), mint = 7)
        lookup.actions += (2..21).map { buy(it, 200L + it, EVENING.minus(Duration.ofMinutes(20)).plusSeconds(it.toLong()), mint = if (it == 21) 7 else it + 100) }
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)
        capped.discover()
        items.save(item(signature(21).value).apply {
            state = AutomationItemState.CAPTURING
            step = AutomationStep.WALLET
            stepAttempt = 1
            captureSessionId = "auto-create_session-x-1"
        })

        capped.discover()

        assertEquals(signature(21).value, items.findAll().single { it.mint == AutomationFixtures.mint(7).value }.signature)
        assertEquals(AutomationItemState.CAPTURING, item(signature(21).value).state)
        assertEquals(20, items.count())
    }

    @Test
    fun `a first scan cut short before any Pump-fun action keeps its cursor, and the next scan continues from it`() {
        lookup.otherTransactions += (500..524).map { signature(it) to 2000L + it }
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofHours(1)))
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)

        capped.discover()
        val started = records.toDiscoveryWatermark(watermarks.findById(WALLET).orElseThrow())
        val shownWhileNoAction = query(properties(), clock, FakeApiUsageReports()).status().watermark
        clock.now = EVENING.plusSeconds(600)
        capped.discover()

        assertEquals(DiscoveryCursor.ORIGIN, started.cursor)
        assertNull(shownWhileNoAction, "no watermark is shown before the first action is found")
        assertEquals(signature(505).value, started.resumeBefore)
        assertEquals(EVENING.minus(Duration.ofHours(12)), started.resumeSince)
        assertNull(started.discoveredThrough)
        assertEquals(signature(505), lookup.requests.last().first.before)
        assertEquals(listOf(signature(1).value), items.findAll().map { it.signature })
        val finished = records.toDiscoveryWatermark(watermarks.findById(WALLET).orElseThrow())
        assertEquals(cursor(1, 100), finished.cursor)
        assertNull(finished.resumeBefore)
        assertNull(finished.resumeSince)
        assertEquals(EVENING.minusSeconds(60), finished.discoveredThrough, "discovery is complete through the start of its first catch-up, less the margin")
    }

    @Test
    fun `a first catch-up whose older history holds no trade stops at the lookback by the listing's block times`() {
        val lookback = EVENING.minus(Duration.ofHours(12))
        lookup.actions += buy(1, 5000, EVENING.minus(Duration.ofHours(1)))
        listed((500..519), slot = 4000, at = EVENING.minus(Duration.ofHours(2)))
        listed((600..699), slot = 1000, at = lookback.minus(Duration.ofHours(1)))
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)

        capped.discover()
        clock.now = EVENING.plusSeconds(600)
        capped.discover()

        assertEquals(listOf(null, signature(501)), lookup.requests.map { it.first.before })
        val finished = records.toDiscoveryWatermark(watermarks.findById(WALLET).orElseThrow())
        assertNull(finished.resumeBefore, "the catch-up ends where the listing passes the lookback")
        assertNull(finished.resumeSince)
        assertEquals(cursor(1, 5000), finished.cursor)
    }

    @Test
    fun `a later scan's buy within the idle time joins the open session from the earlier scan`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofMinutes(10)))
        val service = discovery(lookup, properties(), clock)
        service.discover()

        lookup.actions += buy(2, 200, EVENING.plus(Duration.ofMinutes(40)))
        clock.now = EVENING.plus(Duration.ofMinutes(45))
        service.discover()

        assertEquals(1, sessions.count())
        assertEquals(EVENING.plus(Duration.ofMinutes(40)), sessions.findAll().single().lastBuyAt)
    }

    @Test
    fun `the first scan reaches back the initial lookback only`() {
        lookup.actions += buy(1, 100, EVENING.minus(Duration.ofHours(12)).minusSeconds(1))
        lookup.actions += buy(2, 101, EVENING.minus(Duration.ofHours(12)))

        discovery(lookup, properties(), clock).discover()

        assertEquals(listOf(signature(2).value), items.findAll().map { it.signature })
    }

    @Test
    fun `a rate limit skips the tick and stores nothing`() {
        lookup.actions += buy(1, 100, EVENING.minusSeconds(300))
        lookup.unavailable = WalletActivityUnavailable.PROVIDER_RATE_LIMITED

        val outcome = discovery(lookup, properties(), clock).discover()

        assertEquals(DiscoveryOutcome.Unavailable(WalletActivityUnavailable.PROVIDER_RATE_LIMITED), outcome)
        assertEquals(0, items.count())
        assertEquals(0, watermarks.count())
        assertEquals(WalletActivityUnavailable.PROVIDER_RATE_LIMITED, runtime.heartbeat().discoveryProblem)
    }

    @Test
    fun `an unconfigured RPC skips the tick and is reported until a scan succeeds`() {
        lookup.actions += buy(1, 100, EVENING.minusSeconds(300))
        lookup.unavailable = WalletActivityUnavailable.RPC_NOT_CONFIGURED
        val service = discovery(lookup, properties(), clock)

        service.discover()
        val reported = runtime.heartbeat().discoveryProblem
        lookup.unavailable = null
        service.discover()

        assertEquals(WalletActivityUnavailable.RPC_NOT_CONFIGURED, reported)
        assertNull(runtime.heartbeat().discoveryProblem)
        assertEquals(EVENING, runtime.heartbeat().lastDiscoveryAt)
        assertEquals(1, items.count())
    }

    @Test
    fun `outside the polling window nothing is read`() {
        clock.now = Instant.parse("2026-11-02T07:00:00Z")

        assertEquals(DiscoveryOutcome.OutsideWindow, discovery(lookup, properties(), clock).discover())
        assertTrue(lookup.requests.isEmpty())
    }

    @Test
    fun `always-on scans at 14 00 local, where window mode reads nothing, and never consults the window`() {
        lookup.actions += buy(1, 100, local(2, "13:55"))
        clock.now = local(2, "14:00")

        val outcome = discovery(lookup, properties(pollAlways = true), clock).discover()

        assertTrue(outcome is DiscoveryOutcome.Recorded)
        assertEquals(1, items.count())
        assertEquals(DiscoveryOutcome.OutsideWindow, discovery(FakeWalletActivityLookup(), properties(), clock).discover())
    }

    @Test
    fun `a manual pass ignores the window in window mode, scanning like a scheduled one`() {
        lookup.actions += buy(1, 100, local(2, "13:55"))
        clock.now = local(2, "14:00")
        val service = discovery(lookup, properties(), clock)

        assertEquals(DiscoveryOutcome.OutsideWindow, service.discover())
        val outcome = service.discover(ignoreWindow = true)

        assertTrue(outcome is DiscoveryOutcome.Recorded)
        assertEquals(1, items.count())
        assertEquals(local(2, "14:00"), runtime.heartbeat().lastDiscoveryAt)
    }

    @Test
    fun `buys at 11 50 and 12 10 open two sessions on two trading days though 20 minutes apart`() {
        lookup.actions += buy(1, 100, local(2, "11:50"))
        lookup.actions += buy(2, 101, local(2, "12:10"))

        discovery(lookup, properties(), clock).discover()

        val byBuy = sessions.findAll().sortedBy { it.firstBuyAt }
        assertEquals(listOf(local(2, "11:50"), local(2, "12:10")), byBuy.map { it.firstBuyAt })
        assertEquals(byBuy.map { it.id }, listOf(signature(1), signature(2)).map { item(it.value).sessionId })
    }

    @Test
    fun `a buy at exactly 12 00 starts the new day's session`() {
        lookup.actions += buy(1, 100, local(2, "11:59:59"))
        lookup.actions += buy(2, 101, local(2, "12:00"))

        discovery(lookup, properties(), clock).discover()

        assertEquals(2, sessions.count())
    }

    @Test
    fun `a catch-up buy within the idle time of two sessions of different days joins only the one of its own day`() {
        lookup.actions += buy(1, 100, local(2, "11:30"))
        lookup.actions += buy(2, 200, local(2, "12:20"))
        val capped = discovery(lookup, properties(), clock)
        capped.discover()
        val earlierDay = item(signature(1).value).sessionId
        val laterDay = item(signature(2).value).sessionId
        lookup.actions += buy(3, 300, local(2, "11:55"))
        lookup.actions += buy(4, 301, local(2, "12:05"))
        clock.now = EVENING.plusSeconds(600)

        capped.discover()

        assertEquals(2, sessions.count(), "no bridge between the days")
        assertEquals(earlierDay, item(signature(3).value).sessionId)
        assertEquals(laterDay, item(signature(4).value).sessionId)
        assertEquals(listOf(local(2, "11:30"), local(2, "11:55")), sessions.findOneById(earlierDay)?.let { listOf(it.firstBuyAt, it.lastBuyAt) })
        assertEquals(listOf(local(2, "12:05"), local(2, "12:20")), sessions.findOneById(laterDay)?.let { listOf(it.firstBuyAt, it.lastBuyAt) })
    }

    @Test
    fun `a buy below the filter is counted on the session of its own trading day`() {
        lookup.actions += buy(1, 100, local(2, "11:50"))
        lookup.actions += buy(2, 101, local(2, "12:00"))
        lookup.actions += buy(3, 102, local(2, "12:05"), lamports = SKIPPED_LAMPORTS)

        discovery(lookup, properties(), clock).discover()

        assertEquals(listOf(0, 1), sessions.findAll().sortedBy { it.firstBuyAt }.map { it.belowFilterCount })
    }

    @Test
    fun `a replacement that leaves buys on two trading days splits the session at the boundary`() {
        val legacy = AutomationRows.session("legacy", AutomationSessionState.OPEN, local(2, "11:50"), firstItemId = "item-1")
            .apply { lastBuyAt = local(2, "12:30") }
        sessions.save(legacy)
        items.saveAll(listOf(
            AutomationRows.item(1, "legacy", blockTime = local(2, "11:50"), mint = 1),
            AutomationRows.item(2, "legacy", blockTime = local(2, "12:10"), mint = 2),
            AutomationRows.item(3, "legacy", blockTime = local(2, "12:30"), mint = 7),
        ))
        lookup.actions += buy(30, 300, local(2, "10:30"), mint = 7)
        clock.now = local(2, "22:00")

        discovery(lookup, properties(), clock).discover()

        assertEquals(listOf(local(2, "11:50"), local(2, "12:10")), listOf("item-1", "item-2").map { items.findOneById(it)?.blockTime })
        val kept = sessions.findOneById("legacy") ?: error("kept")
        assertEquals(listOf(local(2, "11:50"), local(2, "11:50")), listOf(kept.firstBuyAt, kept.lastBuyAt))
        val split = sessions.findOneById(items.findOneById("item-2")?.sessionId ?: error("item")) ?: error("split")
        assertTrue(split.id != kept.id)
        assertEquals(listOf(local(2, "12:10"), local(2, "12:10")), listOf(split.firstBuyAt, split.lastBuyAt))
        assertEquals(null, items.findOneById("item-3"), "the earlier buy took the item")
        assertEquals(3, sessions.count(), "the earlier buy is a session of its own, on the day before")
    }

    @Test
    fun `the first scan sets the coverage start to the clock less the initial lookback and later scans keep it`() {
        lookup.actions += buy(1, 100, EVENING.minusSeconds(300))
        val service = discovery(lookup, properties(), clock)

        service.discover()
        clock.now = EVENING.plusSeconds(600)
        lookup.actions += buy(2, 101, EVENING.plusSeconds(300))
        service.discover()

        assertEquals(EVENING.minus(Duration.ofHours(12)), watermarks.findById(WALLET).orElseThrow().coveredFrom)
    }

    @Test
    fun `a first scan cut short by the page limit also sets the coverage start, and the catch-up that completes it keeps it`() {
        lookup.actions += (1..30).map { buy(it, 1000L + it, EVENING.minusSeconds(100L - it)) }
        val capped = discovery(lookup, properties(maxDiscoveryPages = 1), clock)

        capped.discover()
        val atFirst = watermarks.findById(WALLET).orElseThrow().coveredFrom
        clock.now = EVENING.plusSeconds(600)
        capped.discover()

        assertEquals(EVENING.minus(Duration.ofHours(12)), atFirst)
        assertEquals(EVENING.minus(Duration.ofHours(12)), watermarks.findById(WALLET).orElseThrow().coveredFrom)
    }

    @Test
    fun `a watermark stored without a coverage start gets one on the next scan, the earliest session's creation less the initial lookback`() {
        watermarks.saveAndFlush(AutomationWatermarkEntity(WALLET, 1, "sig-0", 2, -1, EVENING.minusSeconds(3600)))
        val firstSeen = EVENING.minus(Duration.ofHours(30))
        sessions.save(AutomationRows.session("s-new", AutomationSessionState.CLOSED, EVENING.minus(Duration.ofHours(6))))
        sessions.save(AutomationRows.session("s-old", AutomationSessionState.CLOSED, firstSeen.plusSeconds(60), createdAt = firstSeen))

        discovery(lookup, properties(), clock).discover()

        assertEquals(firstSeen.minus(Duration.ofHours(12)), watermarks.findById(WALLET).orElseThrow().coveredFrom)
    }

    @Test
    fun `a watermark without a coverage start and without sessions takes its own update time less the initial lookback`() {
        val updated = EVENING.minusSeconds(3600)
        watermarks.saveAndFlush(AutomationWatermarkEntity(WALLET, 1, "sig-0", 2, -1, updated))

        discovery(lookup, properties(), clock).discover()

        assertEquals(updated.minus(Duration.ofHours(12)), watermarks.findById(WALLET).orElseThrow().coveredFrom, "the stored update time, before this scan moved it")
    }

    @Test
    fun `a stored coverage start is never moved by a later scan`() {
        val coveredFrom = EVENING.minus(Duration.ofDays(3))
        watermarks.saveAndFlush(AutomationWatermarkEntity(WALLET, 1, "sig-0", 2, -1, EVENING.minusSeconds(3600)).also { it.coveredFrom = coveredFrom })
        sessions.save(AutomationRows.session("s-old", AutomationSessionState.CLOSED, EVENING.minus(Duration.ofHours(30))))

        discovery(lookup, properties(), clock).discover()

        assertEquals(coveredFrom, watermarks.findById(WALLET).orElseThrow().coveredFrom)
    }

    private fun local(day: Int, time: String): Instant = LocalDateTime.parse("2026-11-0${day}T${if (time.length == 5) "$time:00" else time}").toInstant(ZoneOffset.ofHours(5))

    /** Transactions [range] with no Pump.fun action, listed at slot [slot] plus their number and at block time [at]. */
    private fun listed(range: IntRange, slot: Long, at: Instant) = range.forEach {
        lookup.otherTransactions += signature(it) to slot + it
        lookup.otherBlockTimes[signature(it)] = at
    }

    /** Discovers [buys] among [fillers] with a fresh lookup and returns each session's buys and span. */
    private fun sessionsOf(buys: List<WalletAction>, fillers: List<Pair<TransactionSignature, Long>>, maxDiscoveryPages: Int): Set<Triple<Set<String>, Instant, Instant>> {
        val fresh = FakeWalletActivityLookup()
        fresh.actions += buys
        fresh.otherTransactions += fillers
        val scans = discovery(fresh, properties(maxDiscoveryPages = maxDiscoveryPages), MutableClock(EVENING))
        repeat(3) { scans.discover() }
        val bySession = items.findAll().groupBy({ it.sessionId }, { it.signature })
        return sessions.findAll().map { Triple(bySession.getValue(it.id).toSet(), it.firstBuyAt, it.lastBuyAt) }.toSet()
    }

    private fun cursor(n: Int, slot: Long) = DiscoveryCursor(slot, signature(n).value, 2, DiscoveryCursor.TOP_LEVEL_INNER_INDEX)

    private fun watermark(): DiscoveryCursor? =
        watermarks.findById(WALLET).orElse(null)?.let { DiscoveryCursor(it.slot, it.signature, it.instructionIndex, it.innerIndex) }

    private fun item(signature: String): AutomationItemEntity = items.findAll().single { it.signature == signature }

    private fun sha256(text: String): String =
        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
