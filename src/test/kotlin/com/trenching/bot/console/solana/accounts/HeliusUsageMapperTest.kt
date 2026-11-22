package com.trenching.bot.console.solana.accounts

import com.trenching.bot.console.solana.accounts.infrastructure.helius.HeliusUsageMapper
import com.trenching.bot.console.solana.accounts.infrastructure.helius.HeliusUsageResponse
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class HeliusUsageMapperTest {
    private fun response(
        remaining: String? = "100",
        prepaid: String? = null,
        used: String? = "1",
        limit: String? = "1000",
    ) = HeliusUsageResponse(
        creditsRemaining = remaining?.let(::BigDecimal),
        prepaidCreditsRemaining = prepaid?.let(::BigDecimal),
        creditsUsed = used?.let(::BigDecimal),
        creditCycle = HeliusUsageResponse.CreditCycle(end = "2026-12-05"),
        subscriptionDetails = HeliusUsageResponse.SubscriptionDetails(creditsLimit = limit?.let(::BigDecimal), plan = "Free"),
    )

    @Test
    fun `the plan's credits and the prepaid credits are added and floored once`() {
        assertEquals(1L, HeliusUsageMapper.toBalance(response(remaining = "0.5", prepaid = "0.5"))?.creditsRemaining)
        assertEquals(151L, HeliusUsageMapper.toBalance(response(remaining = "100.6", prepaid = "50.6"))?.creditsRemaining)
        assertEquals(100L, HeliusUsageMapper.toBalance(response(remaining = "100.9"))?.creditsRemaining)
    }

    @Test
    fun `prepaid credits alone keep an exhausted plan alive`() {
        assertEquals(40L, HeliusUsageMapper.toBalance(response(remaining = "0", prepaid = "40"))?.creditsRemaining)
    }

    @Test
    @Timeout(5)
    fun `an absurd exponent is refused before anything is rescaled`() {
        listOf("1E+999999999", "1E-999999999", "-1E+999999999", "0E+999999999").forEach { absurd ->
            assertNull(HeliusUsageMapper.toBalance(response(remaining = absurd)), "remaining $absurd")
            assertNull(HeliusUsageMapper.toBalance(response(prepaid = absurd)), "prepaid $absurd")
            assertNull(HeliusUsageMapper.toBalance(response(used = absurd)), "used $absurd")
            assertNull(HeliusUsageMapper.toBalance(response(limit = absurd)), "limit $absurd")
        }
    }

    @Test
    fun `a number beyond a long or below zero is refused`() {
        assertNull(HeliusUsageMapper.toBalance(response(remaining = "9223372036854775808")))
        assertNull(HeliusUsageMapper.toBalance(response(remaining = "9223372036854775807", prepaid = "1")))
        assertNull(HeliusUsageMapper.toBalance(response(prepaid = "-1")))
        assertNull(HeliusUsageMapper.toBalance(response(remaining = null)))
        assertNotNull(HeliusUsageMapper.toBalance(response(remaining = "9223372036854775807")))
    }
}
