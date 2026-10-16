package com.trenching.bot.console.domain

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class PercentConversionTest {
    private val levels = SimulationConfigLimits.CURRENT.dipBps

    @ParameterizedTest
    @CsvSource("20,2000", "15.5,1550", "0.01,1", "99.99,9999", "20.00,2000", "007,700")
    fun `a percent with at most two decimals converts to exact basis points`(percent: String, bps: Int) {
        assertEquals(PercentToBps.Converted(bps), PercentConversion.toBps(percent, levels))
    }

    @ParameterizedTest
    @ValueSource(strings = ["20.255", "99.995", "-1", "1e2", "", " ", " 20", "20.", ".5", "+20", "20%", "abc"])
    fun `a value that is not a plain percent with at most two decimals is malformed`(percent: String) {
        assertEquals(PercentToBps.Malformed, PercentConversion.toBps(percent, levels))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "0.00", "100", "100.00", "99999999999999999999999"])
    fun `a percent outside the basis point range is out of range`(percent: String) {
        assertEquals(PercentToBps.OutOfRange(levels), PercentConversion.toBps(percent, levels))
    }

    @ParameterizedTest
    @CsvSource("2000,20", "1550,15.5", "1,0.01", "9999,99.99", "125,1.25", "0,0", "-11,-0.11", "-2000,-20")
    fun `basis points convert to a plain percent string without trailing zeros`(bps: Long, percent: String) {
        assertEquals(percent, PercentConversion.toPercent(bps))
    }

    @Test
    fun `every level converts to percent and back to the same basis points`() {
        (1..9999).forEach { bps ->
            assertEquals(PercentToBps.Converted(bps), PercentConversion.toBps(PercentConversion.toPercent(bps.toLong()), levels), "bps=$bps")
        }
    }

    @Test
    fun `a basis point range converts to its percent bounds`() {
        assertEquals("0.01" to "99.99", PercentConversion.toPercent(levels.min) to PercentConversion.toPercent(requireNotNull(levels.max)))
    }
}
