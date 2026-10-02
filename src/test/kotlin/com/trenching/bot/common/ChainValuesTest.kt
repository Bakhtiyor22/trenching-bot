package com.trenching.bot.common

import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class ChainValuesTest {
    @Test
    fun `addresses require base58 encoding of exactly 32 bytes`() {
        assertEquals("11111111111111111111111111111111", WalletAddress.of("11111111111111111111111111111111").value)
        listOf("", "0OIl", "1".repeat(31), "1".repeat(33)).forEach {
            assertFailsWith<IllegalArgumentException> { WalletAddress.of(it) }
        }
    }

    @Test
    fun `raw amounts preserve the entire unsigned 64 bit range`() {
        val amount = TokenAmount.of(BigInteger("18446744073709551615"), 9)
        assertEquals("18446744073.709551615000", amount.quantity().toPlainString())
        assertFailsWith<IllegalArgumentException> { TokenAmount.of(BigInteger("18446744073709551616"), 9) }
        assertFailsWith<IllegalArgumentException> { TokenAmount.of(BigInteger.ONE.negate(), 9) }
        assertFailsWith<IllegalArgumentException> { TokenAmount.of(BigInteger.ONE, 256) }
    }

    @Test
    fun `slots reject negatives and target overflow`() {
        assertFailsWith<IllegalArgumentException> { Slot.of(-1) }
        assertFailsWith<ArithmeticException> { Slot.of(Long.MAX_VALUE).plus(1) }
    }
}
