package com.trenching.bot.console.automation.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Plan Rule 28: always-on scans at every hour; window mode is exactly the polling window. */
class DiscoveryModeTest {
    private val window = PollWindow(LocalTime.of(18, 0), LocalTime.of(5, 0), ZoneOffset.ofHours(5))
    private val midnight = Instant.parse("2026-11-02T00:00:00Z")

    @Test
    fun `always-on scans at every hour of the day`() {
        assertTrue((0 until 24).all { hour -> DiscoveryMode.Always.scansAt(midnight.plus(Duration.ofHours(hour.toLong()))) })
    }

    @Test
    fun `window mode scans exactly when the polling window is open`() {
        val mode = DiscoveryMode.Window(window)

        (0 until 48).map { midnight.plus(Duration.ofMinutes(30L * it)) }.forEach { at -> assertEquals(window.contains(at), mode.scansAt(at), at.toString()) }
        assertTrue(mode.scansAt(Instant.parse("2026-11-02T13:00:00Z")), "18:00 local")
        assertFalse(mode.scansAt(Instant.parse("2026-11-02T09:00:00Z")), "14:00 local")
    }

    @Test
    fun `the modes are named for the status`() {
        assertEquals("ALWAYS", DiscoveryMode.Always.label)
        assertEquals("WINDOW", DiscoveryMode.Window(window).label)
    }
}
