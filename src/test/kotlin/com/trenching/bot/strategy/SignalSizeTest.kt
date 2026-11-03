package com.trenching.bot.strategy

import com.trenching.bot.strategy.domain.SignalSize
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SignalSizeTest {
    private val minimum = BigDecimal("1.9")

    @Test
    fun `the signal is the curve payment plus the protocol and creator fees, each rounded up to a lamport`() {
        assertEquals(BigInteger.valueOf(10_005), SignalSize.lamports(BigInteger.valueOf(10_001), 1, 1))
        assertEquals(BigInteger.valueOf(1_900_000_000), SignalSize.lamports(CURVE_PAYMENT_AT_MINIMUM, 95, 30))
        assertEquals(BigInteger.valueOf(1_876_543_209), SignalSize.lamports(CURVE_PAYMENT_AT_MINIMUM, 0, 0))
    }

    @Test
    fun `a signal of exactly the minimum fails and one lamport more passes`() {
        val atMinimum = SignalSize.lamports(CURVE_PAYMENT_AT_MINIMUM, 95, 30)
        val oneLamportMore = SignalSize.lamports(CURVE_PAYMENT_AT_MINIMUM.add(BigInteger.ONE), 95, 30)

        assertEquals(BigInteger.valueOf(1_900_000_001), oneLamportMore)
        assertFalse(SignalSize.exceeds(atMinimum, minimum))
        assertTrue(SignalSize.exceeds(oneLamportMore, minimum))
    }

    @Test
    fun `a 1,88 SOL curve payment at 95 and 30 basis points pays 1,9035 SOL and passes`() {
        val signal = SignalSize.lamports(BigInteger.valueOf(1_880_000_000), 95, 30)

        assertEquals(BigInteger.valueOf(1_903_500_000), signal)
        assertTrue(SignalSize.exceeds(signal, minimum))
    }

    @Test
    fun `the minimum converts to lamports exactly, whatever its scale`() {
        assertEquals(BigInteger.valueOf(1_900_000_000), SignalSize.minimumLamports(BigDecimal("1.900000000000")))
        assertEquals(BigInteger.ONE, SignalSize.minimumLamports(BigDecimal("0.000000001")))
        assertFailsWith<ArithmeticException> { SignalSize.minimumLamports(BigDecimal("0.0000000001")) }
    }

    private companion object {
        /** Pays 1,876,543,209 + 17,827,161 + 5,629,630 = 1,900,000,000 lamports at 95 and 30 basis points. */
        val CURVE_PAYMENT_AT_MINIMUM: BigInteger = BigInteger.valueOf(1_876_543_209)
    }
}
