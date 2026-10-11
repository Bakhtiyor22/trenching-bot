package com.trenching.bot.protocol.solana

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Test

class TokenAccountAddressTest {
    @Test
    fun `derives the wallet's Token-2022 associated token account for the target mint`() {
        assertEquals("CKT5rabpEHRgvHU1M1DKeN1b1MhJnRr8ZLkHAajSCEmZ",
            TokenAccountAddress.associated(DECU, SolanaPrograms.TOKEN_2022, "hoSDHBQR236CoSLptRpDKPSU1JbcG2Ry52X1iq6pump"))
    }

    @Test
    fun `reproduces the associated user and associated bonding curve accounts of the recorded Pump fun buy`() {
        assertEquals("4CnXbpxS5UHLmeEnj9MJrAGdQCSZpDotTCAva85zQUYi", TokenAccountAddress.associated(DECU, SolanaPrograms.TOKEN_2022, RECORDED_MINT))
        assertEquals("4zqhUNACrbcc3wM8wPN5mYAHAk3TjmhiiDL8GMHG3fEC",
            TokenAccountAddress.associated("Bo82e4Upnsdg9zDeM6wPpckRyuzkLZovJucKCQ2ivUye", SolanaPrograms.TOKEN_2022, RECORDED_MINT))
    }

    @Test
    fun `derives a classic Token program account seen in the recorded exact quote in buy`() {
        assertEquals("A6EBX5NLKXofMb9Xg7fW9Pdhcuo3SQCY2EQW7MCVmx36",
            TokenAccountAddress.associated(DECU, SolanaPrograms.TOKEN, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"))
    }

    @Test
    fun `the token program is a seed so Token and Token-2022 give different accounts`() {
        assertNotEquals(TokenAccountAddress.associated(DECU, SolanaPrograms.TOKEN, RECORDED_MINT),
            TokenAccountAddress.associated(DECU, SolanaPrograms.TOKEN_2022, RECORDED_MINT))
    }

    @Test
    fun `refuses a malformed owner program or mint`() {
        assertFailsWith<IllegalArgumentException> { TokenAccountAddress.associated("not-a-key", SolanaPrograms.TOKEN, RECORDED_MINT) }
        assertFailsWith<IllegalArgumentException> { TokenAccountAddress.associated(DECU, "not-a-key", RECORDED_MINT) }
        assertFailsWith<IllegalArgumentException> { TokenAccountAddress.associated(DECU, SolanaPrograms.TOKEN, "1".repeat(64)) }
    }

    private companion object {
        const val DECU = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        const val RECORDED_MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
    }
}
