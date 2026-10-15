package com.trenching.bot.marketdata

import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.domain.TradeSide
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ObservedTradeTest {
    private val u64Max = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

    @Test
    fun `a trade keeps its actor side and raw amounts`() {
        val trade = ObservedTrade.of(TRADER, TradeSide.SELL, BigInteger("2500000000"), BigInteger("123456789"))

        assertEquals(TRADER, trade.trader)
        assertEquals(TradeSide.SELL, trade.side)
        assertEquals(BigInteger("2500000000"), trade.solLamports)
        assertEquals(BigInteger("123456789"), trade.tokenRaw)
    }

    @Test
    fun `amounts of one and of the largest u64 are accepted`() {
        val smallest = ObservedTrade.of(TRADER, TradeSide.BUY, BigInteger.ONE, BigInteger.ONE)
        val largest = ObservedTrade.of(TRADER, TradeSide.BUY, u64Max, u64Max)

        assertEquals(BigInteger.ONE, smallest.solLamports)
        assertEquals(u64Max, largest.tokenRaw)
    }

    @Test
    fun `zero, negative and above u64 amounts are refused with their values`() {
        listOf(BigInteger.ZERO, BigInteger.valueOf(-1), u64Max.add(BigInteger.ONE)).forEach { invalid ->
            val sol = assertFailsWith<IllegalArgumentException> { ObservedTrade.of(TRADER, TradeSide.BUY, invalid, BigInteger.ONE) }
            val token = assertFailsWith<IllegalArgumentException> { ObservedTrade.of(TRADER, TradeSide.BUY, BigInteger.ONE, invalid) }
            assertTrue(sol.message.orEmpty().contains("solLamports=$invalid"), sol.message)
            assertTrue(token.message.orEmpty().contains("tokenRaw=$invalid"), token.message)
        }
    }

    @Test
    fun `a trader that is not a 32 byte base58 public key is refused with its value`() {
        listOf("", "0OIl", "1".repeat(31), "z".repeat(45), "1".repeat(1_000)).forEach { invalid ->
            val failure = assertFailsWith<IllegalArgumentException> { ObservedTrade.of(invalid, TradeSide.BUY, BigInteger.ONE, BigInteger.ONE) }
            assertTrue(failure.message.orEmpty().contains("trader=${invalid.take(45)}"), failure.message)
        }
    }

    private companion object {
        const val TRADER = "HhnetdgMGNq5nYHJ8ejPDeB6Mk6C1jTGiuofDYN3eWWR"
    }
}
