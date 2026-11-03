package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationFixtures.WALLET
import com.trenching.bot.console.automation.AutomationRows.evaluation
import com.trenching.bot.console.automation.AutomationRows.item
import com.trenching.bot.console.automation.AutomationRows.session
import com.trenching.bot.console.automation.AutomationRows.sweepRun
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.DiscoveryCursor
import com.trenching.bot.console.automation.domain.EvaluationStatus
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkEntity
import com.trenching.bot.console.automation.infrastructure.persistence.DiscoveryCursorColumns
import java.time.Duration
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
    fun `a second evaluation for the same session, plan and label is refused`() {
        evaluations.saveAndFlush(evaluation("eval-a", "s1", "V00", EvaluationStatus.NO_RESULT))

        assertFailsWith<DataIntegrityViolationException> { evaluations.saveAndFlush(evaluation("eval-b", "s1", "V00", EvaluationStatus.SUCCESS)) }
    }

    @Test
    fun `due items are the pending ones of open or closing sessions whose next action has come, oldest buy first, one page at a time`() {
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
        ))
        val pending = AutomationItemState.entries.filterNot { it.terminal }
        val active = listOf(AutomationSessionState.OPEN, AutomationSessionState.CLOSING)

        val first = items.findDue(pending, active, EVENING, PageRequest.of(0, 2)).map { it.id }
        val second = items.findDue(pending, active, EVENING, PageRequest.of(1, 2)).map { it.id }

        assertEquals(listOf("item-2", "item-1"), first)
        assertEquals(listOf("item-5", "item-6"), second)
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
    fun `a new evaluation with the id of a stored one is inserted and refused, never merged over it`() {
        evaluations.saveAndFlush(evaluation("eval-a", "s1", "V00", EvaluationStatus.NO_RESULT))

        assertFailsWith<DataIntegrityViolationException> { evaluations.saveAndFlush(evaluation("eval-a", "s1", "V00", EvaluationStatus.SUCCESS)) }
        assertEquals(EvaluationStatus.NO_RESULT, evaluations.findAll().single().status)
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
}
