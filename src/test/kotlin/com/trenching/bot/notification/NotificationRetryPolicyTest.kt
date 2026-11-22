package com.trenching.bot.notification

import com.trenching.bot.notification.domain.DeliveryCategory
import com.trenching.bot.notification.domain.DeliveryDecision
import com.trenching.bot.notification.domain.DeliveryResult
import com.trenching.bot.notification.domain.NotificationRetryPolicy
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class NotificationRetryPolicyTest {
    private val policy = NotificationRetryPolicy(maxAttempts = 54)

    @ParameterizedTest
    @CsvSource("1,30", "2,60", "3,120", "4,240", "5,480", "6,960", "7,1800", "9,1800")
    fun `backs off from 30 seconds doubling up to 30 minutes`(attempt: Int, seconds: Long) {
        val decision = policy.decide(attempt, DeliveryResult.RetryLater(DeliveryCategory.UNAVAILABLE, null), NOW)

        assertEquals(DeliveryDecision.RetryAt(NOW.plusSeconds(seconds), DeliveryCategory.UNAVAILABLE), decision)
    }

    @Test
    fun `uses the stated wait instead of the backoff`() {
        val decision = policy.decide(5, DeliveryResult.RetryLater(DeliveryCategory.RATE_LIMITED, Duration.ofSeconds(12)), NOW)

        assertEquals(DeliveryDecision.RetryAt(NOW.plusSeconds(12), DeliveryCategory.RATE_LIMITED), decision)
    }

    @ParameterizedTest
    @CsvSource("86400,86400", "86401,86400", "999999999999,86400")
    fun `caps the wait the provider asks for at 24 hours`(askedSeconds: Long, expectedSeconds: Long) {
        val decision = policy.decide(1, DeliveryResult.RetryLater(DeliveryCategory.RATE_LIMITED, Duration.ofSeconds(askedSeconds)), NOW)

        assertEquals(DeliveryDecision.RetryAt(NOW.plusSeconds(expectedSeconds), DeliveryCategory.RATE_LIMITED), decision)
    }

    @Test
    fun `the backoff for a claim is the one a retry without a stated wait gets`() {
        assertEquals(NOW.plusSeconds(120), policy.claimUntil(3, NOW))
        assertEquals(NOW.plusSeconds(1800), policy.claimUntil(40, NOW))
    }

    @Test
    fun `gives up when the last allowed attempt asks to retry`() {
        val decision = policy.decide(54, DeliveryResult.RetryLater(DeliveryCategory.TIMEOUT, null), NOW)

        assertEquals(DeliveryDecision.Abandon(DeliveryCategory.TIMEOUT), decision)
    }

    @Test
    fun `gives up at once on a rejection`() {
        assertEquals(DeliveryDecision.Abandon(DeliveryCategory.FORBIDDEN), policy.decide(1, DeliveryResult.Rejected(DeliveryCategory.FORBIDDEN), NOW))
    }

    @Test
    fun `delivered is sent`() {
        assertEquals(DeliveryDecision.Sent, policy.decide(3, DeliveryResult.Delivered, NOW))
    }

    @Test
    fun `needs at least one attempt`() {
        assertFailsWith<IllegalArgumentException> { NotificationRetryPolicy(0) }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-22T10:00:00Z")
    }
}
