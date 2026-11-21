package com.trenching.bot.console.automation.domain

import com.trenching.bot.console.capture.CaptureStopMeanings
import com.trenching.bot.replay.domain.TargetBuyRefusal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AutomationFailureMeaningTest {
    private val unknown = CaptureStopMeanings.meaning("NOT_A_CODE")

    @Test
    fun `each code the automation sets itself has its own clause`() {
        val own = listOf(
            AutomationFailures.CAPTURE_NOT_FOUND, AutomationFailures.BACKFILL_BUDGET, AutomationFailures.MISSING_BLOCK_TIME, AutomationFailures.SWEEP_FAILED,
        )

        own.forEach { assertNotEquals(unknown, AutomationFailures.meaning(it), it) }
        assertEquals(own.size, own.map(AutomationFailures::meaning).toSet().size, "no two codes share a clause")
    }

    @Test
    fun `an operation end state that reaches an item as its code has its own clause`() {
        listOf("INCOMPLETE", "FAILED", "CANCELLED", "BLOCKED").forEach { assertNotEquals(unknown, AutomationFailures.meaning(it), it) }
    }

    @Test
    fun `every other code is explained by the capture console's clause, an unknown one by its fallback`() {
        assertEquals(CaptureStopMeanings.meaning("OVER_CAP"), AutomationFailures.meaning("OVER_CAP"))
        assertEquals(CaptureStopMeanings.meaning("INTERRUPTED"), AutomationFailures.meaning("INTERRUPTED"))
        assertEquals(CaptureStopMeanings.meaning("BUDGET_EXHAUSTED_REQUESTS"), AutomationFailures.meaning("BUDGET_EXHAUSTED_REQUESTS"))
        assertEquals(unknown, AutomationFailures.meaning("NOT_A_CODE"))
    }

    @Test
    fun `a clause is lower case with no full stop so a sentence can end it`() {
        listOf("CAPTURE_NOT_FOUND", "BACKFILL_BUDGET", "MISSING_BLOCK_TIME", "SWEEP_FAILED", "INCOMPLETE", "FAILED", "CANCELLED", "BLOCKED").forEach {
            val clause = AutomationFailures.meaning(it)
            assertFalse(clause.endsWith("."), clause)
            assertTrue(clause.first().isLowerCase(), clause)
        }
    }

    @Test
    fun `each first-buy classification that skips an item has its own clause and an unknown one the fallback`() {
        val clauses = TargetBuyRefusal.entries.map { AutomationFirstBuy.meaning(it.name) }

        assertEquals(TargetBuyRefusal.entries.size, clauses.toSet().size)
        clauses.forEach {
            assertFalse(it.endsWith("."), it)
            assertTrue(it.first().isLowerCase(), it)
        }
        assertEquals(AutomationFirstBuy.meaning(null), AutomationFirstBuy.meaning("SOMETHING_ELSE"))
        assertFalse(AutomationFirstBuy.meaning(null) in clauses)
    }
}
