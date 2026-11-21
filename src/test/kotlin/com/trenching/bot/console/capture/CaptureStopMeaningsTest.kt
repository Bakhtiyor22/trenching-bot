package com.trenching.bot.console.capture

import com.trenching.bot.chaincapture.CaptureCategory
import com.trenching.bot.marketdata.MarketCaptureReasons
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.walletactivity.TokenAccountGap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CaptureStopMeaningsTest {
    private val fallback = CaptureStopMeanings.meaning("NOT_A_KNOWN_CODE")

    @Test
    fun `the budget categories keep their own clauses`() {
        assertEquals("the operation's request budget ran out", CaptureStopMeanings.meaning("REQUESTS"))
        listOf("REQUESTS", "BYTES", "RESPONSE_BYTES", "DEADLINE").forEach { assertNotEquals(fallback, CaptureStopMeanings.meaning(it), it) }
    }

    @Test
    fun `a budget exhausted code reads like its category`() {
        listOf("REQUESTS", "BYTES", "RESPONSE_BYTES", "DEADLINE").forEach {
            assertEquals(CaptureStopMeanings.meaning(it), CaptureStopMeanings.meaning("BUDGET_EXHAUSTED_$it"), it)
        }
    }

    @Test
    fun `every code the capture layer can store has a clause of its own`() {
        val codes = listOf(
            "IO", "TIMEOUT", CaptureCategory.RATE_LIMITED, "UNAVAILABLE", "NOT_CONFIGURED", "INTERRUPTED", "AUTHENTICATION", "HTTP", "MALFORMED", "RPC_ERROR",
            CaptureCategory.UNSUPPORTED_VERSION, CaptureCategory.STOP_REQUESTED, CaptureCategory.SLOT_SKIPPED,
            MarketWindowCoverage.OVER_CAP.name, TokenAccountGap.HISTORY_NOT_EXHAUSTED.name, MarketCaptureReasons.HORIZON_NOT_ELAPSED,
            MarketCaptureReasons.MARKET_HORIZON_NOT_ELAPSED, MarketCaptureReasons.MARKET_LISTED_BEFORE_END, MarketCaptureReasons.MARKET_WINDOW_INVALID,
            "CAPTURE_FAILED", "PROCESS_RESTART", "PAUSED_BY_USER", "CANCELLED_BY_USER", "STALE_ATTEMPT", "RETRY_EXHAUSTED", "BUDGET_EXHAUSTED", "PREREQUISITE",
            "RESUME_IDENTITY", "CAPTURE_INCOMPLETE", "MANIFEST_MISSING", "CUTOFF_DECISION", "REFERENCE_RATES_INCOMPLETE", "CASE_RESULT_MISMATCH",
        )

        val clauses = codes.map { CaptureStopMeanings.meaning(it) }

        assertFalse(clauses.any { it == fallback }, codes.zip(clauses).filter { it.second == fallback }.toString())
        assertEquals(codes.size, clauses.toSet().size, "each code has its own clause")
        assertTrue(CaptureStopMeanings.codes.containsAll(codes))
    }

    @Test
    fun `every clause is lower case without a full stop and no two codes share one`() {
        val clauses = CaptureStopMeanings.codes.associateWith { CaptureStopMeanings.meaning(it) }
        clauses.forEach { (code, clause) ->
            assertTrue(clause.first().isLowerCase(), "$code: $clause")
            assertFalse(clause.endsWith("."), "$code: $clause")
        }
        assertEquals(clauses.size, clauses.values.toSet().size)
    }

    @Test
    fun `the over cap clause is static and names no number`() {
        val clause = CaptureStopMeanings.meaning("OVER_CAP")
        assertEquals("the market window lists more trades than the capture's trade cap, so none were fetched", clause)
        assertFalse(clause.any { it.isDigit() })
    }

    @Test
    fun `an unknown code falls back to a fixed clause`() {
        assertEquals("an unexpected stop code; see the capture console", fallback)
        assertEquals(fallback, CaptureStopMeanings.meaning("BUDGET_EXHAUSTED_NOPE"))
        assertEquals(fallback, CaptureStopMeanings.meaning(""))
    }
}
