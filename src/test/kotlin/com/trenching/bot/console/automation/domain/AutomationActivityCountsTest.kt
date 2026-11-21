package com.trenching.bot.console.automation.domain

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AutomationActivityCountsTest {
    private val since = Instant.parse("2026-11-21T10:00:00Z")
    private val until = Instant.parse("2026-11-21T11:00:00Z")

    @Test
    fun `counts nothing when nothing happened`() {
        val counts = AutomationActivityCounts(since, until)

        assertTrue(counts.isEmpty)
        assertEquals(0, counts.failed)
    }

    @Test
    fun `failed is the total across codes and any count makes it non-empty`() {
        val counts = AutomationActivityCounts(since, until, failedByCode = mapOf("OVER_CAP" to 2, "REQUESTS" to 1))

        assertEquals(3, counts.failed)
        assertFalse(counts.isEmpty)
        assertFalse(AutomationActivityCounts(since, until, retriedByPerson = 1).isEmpty)
    }
}
