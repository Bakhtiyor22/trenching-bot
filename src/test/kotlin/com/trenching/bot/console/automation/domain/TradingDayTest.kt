package com.trenching.bot.console.automation.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Plan Key Decision 3 and Rule 30: a trading day runs from 12:00 to 12:00 UTC+5 and is named by its starting date. */
class TradingDayTest {
    private val start = LocalTime.NOON
    private val zone = ZoneOffset.ofHours(5)
    private val noon = Instant.parse("2026-11-03T07:00:00Z")

    @Test
    fun `11-59-59 and 12-00-00 fall on different days, and a buy exactly at the start belongs to the new day`() {
        val before = TradingDay.of(noon.minusSeconds(1), start, zone)
        val at = TradingDay.of(noon, start, zone)

        assertEquals(LocalDate.parse("2026-11-02"), before.date)
        assertEquals(LocalDate.parse("2026-11-03"), at.date)
        assertNotEquals(before, at)
    }

    @Test
    fun `a night after midnight belongs to the day that started the noon before`() {
        assertEquals(LocalDate.parse("2026-11-03"), TradingDay.of(Instant.parse("2026-11-03T14:00:00Z"), start, zone).date, "19:00 local")
        assertEquals(LocalDate.parse("2026-11-03"), TradingDay.of(Instant.parse("2026-11-03T22:00:00Z"), start, zone).date, "03:00 local the next day")
        assertEquals(LocalDate.parse("2026-11-03"), TradingDay.of(noon.plus(Duration.ofHours(24)).minusMillis(1), start, zone).date)
        assertEquals(LocalDate.parse("2026-11-04"), TradingDay.of(noon.plus(Duration.ofHours(24)), start, zone).date)
    }

    @Test
    fun `a day's range runs from its date at the start to the next date at the start, in the zone`() {
        val range = TradingDay(LocalDate.parse("2026-11-03")).range(start, zone)

        assertEquals(Instant.parse("2026-11-03T07:00:00Z"), range.from)
        assertEquals(Instant.parse("2026-11-04T07:00:00Z"), range.to)
        assertTrue(range.contains(range.from))
        assertFalse(range.contains(range.to), "the end belongs to the next day")
        assertEquals(TradingDay(LocalDate.parse("2026-11-03")), TradingDay.of(range.from, start, zone))
        assertEquals(TradingDay(LocalDate.parse("2026-11-04")), TradingDay.of(range.to, start, zone))
    }

    @Test
    fun `a start other than noon moves the boundary`() {
        val early = LocalTime.of(6, 30)

        assertEquals(LocalDate.parse("2026-11-02"), TradingDay.of(Instant.parse("2026-11-03T01:29:59Z"), early, zone).date, "06:29:59 local")
        assertEquals(LocalDate.parse("2026-11-03"), TradingDay.of(Instant.parse("2026-11-03T01:30:00Z"), early, zone).date, "06:30 local")
        assertEquals(Instant.parse("2026-11-03T01:30:00Z"), TradingDay(LocalDate.parse("2026-11-03")).range(early, zone).from)
    }

    @Test
    fun `the calendar names days and ranges the same way, and lists the days of a span`() {
        val calendar = TradingCalendar(start, zone)
        val day = LocalDate.parse("2026-11-03")

        assertEquals(TradingDay(day), calendar.dayOf(noon))
        assertEquals(TradingDay(day).range(start, zone), calendar.range(TradingDay(day)))
        assertEquals(listOf("2026-11-02", "2026-11-03", "2026-11-04").map { TradingDay(LocalDate.parse(it)) },
            TradingDay(LocalDate.parse("2026-11-02")).through(TradingDay(LocalDate.parse("2026-11-04"))))
        assertEquals(TradingDay(LocalDate.parse("2026-10-28")), TradingDay(day).minusDays(6))
    }
}
