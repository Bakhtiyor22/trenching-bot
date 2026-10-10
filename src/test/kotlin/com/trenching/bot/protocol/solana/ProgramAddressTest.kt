package com.trenching.bot.protocol.solana

import com.trenching.bot.common.Base58
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ProgramAddressTest {
    @Test
    fun `derives the user volume accumulator that Decu's recorded buy passes to Pump fun`() {
        assertEquals(
            ProgramAddress.Derived("Hj2hjN1EAe5pv676kUMf14zbj1ioWEHaiukbHDqw2P3D", 255),
            ProgramAddress.find(listOf(seed("user_volume_accumulator"), key(DECU)), PUMP_FUN),
        )
    }

    @Test
    fun `derives the Pump fun program addresses seen in the recorded buy`() {
        assertEquals(ProgramAddress.Derived("Hq2wp8uJ9jCPsYgNHex8RtqdvMPfVGoYwjvF1ATiwn2Y", 255),
            ProgramAddress.find(listOf(seed("global_volume_accumulator")), PUMP_FUN))
        assertEquals(ProgramAddress.Derived("Ce6TQqeHC9p8KetsN6JsjHK7UTZk7nasjjnr7XxXp9F1", 255),
            ProgramAddress.find(listOf(seed("__event_authority")), PUMP_FUN))
        assertEquals(ProgramAddress.Derived("Bo82e4Upnsdg9zDeM6wPpckRyuzkLZovJucKCQ2ivUye", 255),
            ProgramAddress.find(listOf(seed("bonding-curve"), key("5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs")), PUMP_FUN))
    }

    /** Cross-checked offline with an independent RFC 8032 square-root test; bumps 255 and 254 land on the curve. */
    @Test
    fun `skips candidates that lie on the ed25519 curve`() {
        assertEquals(
            ProgramAddress.Derived("CZhuXtooUDxFt1G1xcSngPFW1PMxnofXMxxiy61UBQaT", 253),
            ProgramAddress.find(listOf(seed("user_volume_accumulator"), key("2AZhVvG43iccBRntR4Z5wyzd4tHJpuqFwaavYyt3k5ZR")), PUMP_FUN),
        )
    }

    @Test
    fun `a signing wallet key is on the curve and derived addresses are not`() {
        assertTrue(ProgramAddress.isOnCurve(key(DECU)))
        listOf("Hj2hjN1EAe5pv676kUMf14zbj1ioWEHaiukbHDqw2P3D", "Hq2wp8uJ9jCPsYgNHex8RtqdvMPfVGoYwjvF1ATiwn2Y",
            "CZhuXtooUDxFt1G1xcSngPFW1PMxnofXMxxiy61UBQaT").forEach { assertFalse(ProgramAddress.isOnCurve(key(it)), it) }
    }

    @Test
    fun `refuses oversized seeds too many seeds and malformed inputs`() {
        assertFailsWith<IllegalArgumentException> { ProgramAddress.find(listOf(ByteArray(33)), PUMP_FUN) }
        assertFailsWith<IllegalArgumentException> { ProgramAddress.find(List(16) { ByteArray(1) }, PUMP_FUN) }
        assertFailsWith<IllegalArgumentException> { ProgramAddress.find(listOf(seed("x")), "not-a-key") }
        assertFailsWith<IllegalArgumentException> { ProgramAddress.isOnCurve(ByteArray(31)) }
    }

    private fun seed(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun key(address: String): ByteArray = requireNotNull(Base58.decode(address))

    private companion object {
        const val PUMP_FUN = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"
        const val DECU = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
    }
}
