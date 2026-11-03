package com.trenching.bot.console.automation.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PollWindowTest {
    private val night = PollWindow(LocalTime.of(18, 0), LocalTime.of(5, 0), ZoneOffset.ofHours(5))

    @Test
    fun `a window crossing midnight is open from its start through midnight to just before its end`() {
        assertFalse(night.contains(Instant.parse("2026-11-02T12:59:59Z")), "17:59:59 local")
        assertTrue(night.contains(Instant.parse("2026-11-02T13:00:00Z")), "18:00 local")
        assertTrue(night.contains(Instant.parse("2026-11-02T19:00:00Z")), "00:00 local")
        assertTrue(night.contains(Instant.parse("2026-11-02T23:59:59Z")), "04:59:59 local")
        assertFalse(night.contains(Instant.parse("2026-11-03T00:00:00Z")), "05:00 local")
    }

    @Test
    fun `the last end at or before an instant is the most recent time the window closed, whether the instant is inside it or not`() {
        val lastEnd = Instant.parse("2026-11-02T00:00:00Z")
        val nextEnd = Instant.parse("2026-11-03T00:00:00Z")

        assertEquals(lastEnd, night.lastEndAtOrBefore(Instant.parse("2026-11-02T12:59:59Z")), "17:59:59 local, outside")
        assertEquals(lastEnd, night.lastEndAtOrBefore(Instant.parse("2026-11-02T13:00:00Z")), "18:00 local, inside")
        assertEquals(lastEnd, night.lastEndAtOrBefore(Instant.parse("2026-11-02T23:59:59Z")), "04:59:59 local, inside")
        assertEquals(nextEnd, night.lastEndAtOrBefore(nextEnd), "05:00 local, the end itself")
        assertEquals(nextEnd, night.lastEndAtOrBefore(Instant.parse("2026-11-03T00:30:00Z")), "05:30 local, outside")
    }

    @Test
    fun `a window within one day ends the same day`() {
        val day = PollWindow(LocalTime.of(9, 0), LocalTime.of(17, 0), ZoneOffset.UTC)

        assertEquals(Instant.parse("2026-11-01T17:00:00Z"), day.lastEndAtOrBefore(Instant.parse("2026-11-02T08:00:00Z")))
        assertEquals(Instant.parse("2026-11-02T17:00:00Z"), day.lastEndAtOrBefore(Instant.parse("2026-11-02T20:00:00Z")))
    }

    @Test
    fun `a window within one day is open between its start and its end`() {
        val day = PollWindow(LocalTime.of(9, 0), LocalTime.of(17, 0), ZoneOffset.UTC)

        assertFalse(day.contains(Instant.parse("2026-11-02T08:59:59Z")))
        assertTrue(day.contains(Instant.parse("2026-11-02T09:00:00Z")))
        assertTrue(day.contains(Instant.parse("2026-11-02T16:59:59Z")))
        assertFalse(day.contains(Instant.parse("2026-11-02T17:00:00Z")))
    }
}
