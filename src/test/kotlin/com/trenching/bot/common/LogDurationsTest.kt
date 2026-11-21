package com.trenching.bot.common

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class LogDurationsTest {

    @Test
    fun `zero is zero seconds`() = assertEquals("0s", LogDurations.compact(Duration.ZERO))

    @Test
    fun `under a minute is whole seconds`() {
        assertEquals("12s", LogDurations.compact(Duration.ofSeconds(12)))
        assertEquals("59s", LogDurations.compact(Duration.ofSeconds(59)))
    }

    @Test
    fun `sub-second part is dropped`() = assertEquals("0s", LogDurations.compact(Duration.ofMillis(999)))

    @Test
    fun `seconds are left out once a minute is reached`() {
        assertEquals("1m", LogDurations.compact(Duration.ofSeconds(75)))
        assertEquals("35m", LogDurations.compact(Duration.ofMinutes(35)))
    }

    @Test
    fun `zero units are left out`() {
        assertEquals("1h", LogDurations.compact(Duration.ofHours(1)))
        assertEquals("1h5m", LogDurations.compact(Duration.ofMinutes(65)))
        assertEquals("2d", LogDurations.compact(Duration.ofDays(2)))
        assertEquals("2d31m", LogDurations.compact(Duration.ofDays(2).plusMinutes(31)))
    }

    @Test
    fun `days hours and minutes together`() =
        assertEquals("2d10h31m", LogDurations.compact(Duration.ofDays(2).plusHours(10).plusMinutes(31).plusSeconds(40)))

    @Test
    fun `a negative duration, as a clock step back gives, is zero seconds and never throws from a log call`() {
        assertEquals("0s", LogDurations.compact(Duration.ofSeconds(-1)))
        assertEquals("0s", LogDurations.compact(Duration.ofDays(-3)))
    }
}
