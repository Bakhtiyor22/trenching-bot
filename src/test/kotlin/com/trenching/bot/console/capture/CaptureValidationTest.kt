package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.CaptureWalletSettings
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class CaptureValidationTest {
    private val wallet = CaptureConsoleFixture.WALLET

    private fun invalidField(settings: CaptureWalletSettings): Map<String, String> =
        assertFailsWith<CaptureRequestException> { CaptureValidation.wallet(settings) }.also { assertEquals(400, it.status) }.fields

    @Test
    fun `the wallet-token scope accepts a base58 mint`() {
        CaptureValidation.wallet(CaptureWalletSettings(wallet, "wallet-token", 100, 5, CaptureTokenFixtures.MINT))
    }

    @Test
    fun `the wallet-token scope requires a mint`() {
        assertEquals(setOf("mint"), invalidField(CaptureWalletSettings(wallet, "wallet-token", 100, 5)).keys)
        assertEquals(setOf("mint"), invalidField(CaptureWalletSettings(wallet, "wallet-token", 100, 5, "")).keys)
    }

    @Test
    fun `the wallet-token scope refuses a mint that is not a 32-byte base58 address`() {
        assertEquals(setOf("mint"), invalidField(CaptureWalletSettings(wallet, "wallet-token", 100, 5, "not-base58-0OIl")).keys)
        assertEquals(setOf("mint"), invalidField(CaptureWalletSettings(wallet, "wallet-token", 100, 5, "1111")).keys)
    }

    @Test
    fun `the other scopes refuse a mint`() {
        listOf("pumpfun-user-buys", "wallet-references").forEach { scope ->
            CaptureValidation.wallet(CaptureWalletSettings(wallet, scope))
            assertEquals(setOf("mint"), invalidField(CaptureWalletSettings(wallet, scope, mint = CaptureTokenFixtures.MINT)).keys)
        }
    }

    @Test
    fun `an unknown scope is refused`() {
        assertEquals(setOf("scope"), invalidField(CaptureWalletSettings(wallet, "wallet-everything")).keys)
    }
}
