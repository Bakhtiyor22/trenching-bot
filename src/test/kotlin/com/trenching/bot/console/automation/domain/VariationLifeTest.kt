package com.trenching.bot.console.automation.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Plan Rule 9: a variation has a verdict row for a trading day exactly when its life covers the day, by the calendar's day boundary. */
class VariationLifeTest {
    private val calendar = TradingCalendar(LocalTime.NOON, ZoneOffset.ofHours(5))

    @Test
    fun `a day inside the life is covered, the day before its creation is not`() {
        val life = VariationLife(activeFrom = local("2026-11-10", "15:00"), archivedAt = local("2026-11-12", "15:00"))

        assertTrue(life.covers(day("2026-11-10"), calendar), "the creation day")
        assertTrue(life.covers(day("2026-11-11"), calendar))
        assertFalse(life.covers(day("2026-11-09"), calendar))
    }

    @Test
    fun `the archive day is covered and the day after it is not`() {
        val life = VariationLife(activeFrom = null, archivedAt = local("2026-11-12", "15:00"))

        assertTrue(life.covers(day("2026-11-12"), calendar))
        assertFalse(life.covers(day("2026-11-13"), calendar))
    }

    @Test
    fun `a variation created before the day start belongs to the previous trading day`() {
        val life = VariationLife(activeFrom = local("2026-11-10", "11:59"), archivedAt = null)

        assertTrue(life.covers(day("2026-11-09"), calendar))
        assertFalse(life.covers(day("2026-11-08"), calendar))
    }

    @Test
    fun `a seeded variation covers every day until it is archived`() {
        val seeded = VariationLife(activeFrom = null, archivedAt = null)

        assertTrue(listOf("2000-01-01", "2026-11-10", "2999-12-31").all { seeded.covers(day(it), calendar) })
    }

    @Test
    fun `an active variation covers every day from its creation on`() {
        val life = VariationLife(activeFrom = local("2026-11-10", "15:00"), archivedAt = null)

        assertTrue(life.covers(day("2026-11-10"), calendar))
        assertTrue(life.covers(day("2027-01-01"), calendar))
        assertEquals(listOf(false, true), listOf("2026-11-09", "2026-11-10").map { life.covers(day(it), calendar) })
    }

    private fun day(date: String) = TradingDay(LocalDate.parse(date))

    private fun local(date: String, time: String): Instant = LocalDateTime.parse("${date}T$time:00").toInstant(ZoneOffset.ofHours(5))
}
