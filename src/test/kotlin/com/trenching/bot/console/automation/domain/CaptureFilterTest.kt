package com.trenching.bot.console.automation.domain

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CaptureFilterTest {
    private val filter = CaptureFilter.of(BigDecimal("1.5"), 300)

    @Test
    fun `the minimum converts to whole lamports`() {
        assertEquals(BigInteger.valueOf(1_500_000_000), filter.minSignalLamports)
    }

    @Test
    fun `a payment whose margin product rounds down to the minimum is not captured`() {
        assertFalse(filter.passes(BigInteger.valueOf(1_456_310_680)), "1,456,310,680 x 1.03 = 1,500,000,000.4 rounds down to the minimum")
    }

    @Test
    fun `one lamport more is captured`() {
        assertTrue(filter.passes(BigInteger.valueOf(1_456_310_681)), "1,456,310,681 x 1.03 = 1,500,000,001.43")
    }

    @Test
    fun `without a margin exactly the minimum is not captured and one lamport more is`() {
        val noMargin = CaptureFilter.of(BigDecimal("1.5"), 0)

        assertFalse(noMargin.passes(BigInteger.valueOf(1_500_000_000)))
        assertTrue(noMargin.passes(BigInteger.valueOf(1_500_000_001)))
    }

    @Test
    fun `a minimum finer than a lamport is refused`() {
        assertFailsWith<IllegalArgumentException> { CaptureFilter.of(BigDecimal("1.5000000001"), 300) }
    }
}
