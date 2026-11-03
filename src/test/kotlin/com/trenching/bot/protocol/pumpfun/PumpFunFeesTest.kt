package com.trenching.bot.protocol.pumpfun

import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class PumpFunFeesTest {
    @Test
    fun `fees round up to the next lamport`() {
        assertEquals(BigInteger.ONE, PumpFunFees.lamports(BigInteger.ONE, 1))
        assertEquals(BigInteger.valueOf(95), PumpFunFees.lamports(BigInteger.valueOf(10_000), 95))
        assertEquals(BigInteger.valueOf(96), PumpFunFees.lamports(BigInteger.valueOf(10_001), 95))
        assertEquals(BigInteger.ZERO, PumpFunFees.lamports(BigInteger.ZERO, 95))
        assertEquals(BigInteger.ZERO, PumpFunFees.lamports(BigInteger.valueOf(10_001), 0))
    }

    @Test
    fun `a paper entry's 0,1 SOL and a standing-curve sale's proceeds keep the fees paper trading charged`() {
        val notional = BigInteger.valueOf(100_000_000)
        val saleProceeds = BigInteger.valueOf(165_902_732)

        assertEquals(BigInteger.valueOf(1_250_000), PumpFunFees.lamports(notional, 95).add(PumpFunFees.lamports(notional, 30)))
        assertEquals(BigInteger.valueOf(1_576_076), PumpFunFees.lamports(saleProceeds, 95))
        assertEquals(BigInteger.valueOf(497_709), PumpFunFees.lamports(saleProceeds, 30))
    }

    @Test
    fun `the decoded event's raw basis points give the same fee as their integer value`() {
        val amount = BigInteger.valueOf(1_876_543_209)

        assertEquals(PumpFunFees.lamports(amount, 95), PumpFunFees.lamports(amount, BigInteger.valueOf(95)))
        assertEquals(BigInteger.valueOf(17_827_161), PumpFunFees.lamports(amount, 95))
    }

    @Test
    fun `a u64 maximum amount at the full rate does not overflow`() {
        val max = BigInteger.ONE.shiftLeft(Long.SIZE_BITS).subtract(BigInteger.ONE)

        assertEquals(max, PumpFunFees.lamports(max, BondingCurve.MAX_BASIS_POINTS))
    }

    @Test
    fun `a negative amount or rate is refused`() {
        assertFailsWith<IllegalArgumentException> { PumpFunFees.lamports(BigInteger.ONE.negate(), 95) }
        assertFailsWith<IllegalArgumentException> { PumpFunFees.lamports(BigInteger.ONE, -1) }
    }
}
