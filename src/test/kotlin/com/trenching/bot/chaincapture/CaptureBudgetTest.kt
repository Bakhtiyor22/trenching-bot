package com.trenching.bot.chaincapture

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class CaptureBudgetTest {
    @Test
    fun `concurrent callers are charged exactly once per granted request and the limit is never exceeded`() {
        val budget = CaptureBudget(maxRequests = 500, maxBytes = Long.MAX_VALUE, initialRequests = 7)
        val granted = AtomicInteger()
        val refused = AtomicInteger()
        runConcurrently(8) {
            repeat(100) {
                try { budget.request(); granted.incrementAndGet() } catch (exception: CaptureBudgetException) {
                    assertEquals("REQUESTS", exception.category)
                    refused.incrementAndGet()
                }
            }
        }
        assertEquals(500, granted.get())
        assertEquals(300, refused.get())
        assertEquals(500, budget.requestsUsed)
        assertEquals(507, budget.cumulativeRequests())
    }

    @Test
    fun `concurrent byte charges add up exactly and include carried over bytes in the cumulative total`() {
        val budget = CaptureBudget(maxRequests = 10, maxBytes = Long.MAX_VALUE, initialBytes = 11)
        runConcurrently(8) { repeat(1000) { budget.bytes(3) } }
        assertEquals(24_000, budget.bytesUsed)
        assertEquals(24_011, budget.cumulativeBytes())
        assertEquals(Long.MAX_VALUE - 24_000, budget.remainingBytes())
    }

    @Test
    fun `exceeding the byte limit or charging a negative count throws the same budget exception as before`() {
        val budget = CaptureBudget(maxRequests = 1, maxBytes = 10)
        budget.bytes(10)
        val over = assertFailsWith<CaptureBudgetException> { budget.bytes(1) }
        assertEquals(listOf("BYTES", 0, 11L), listOf(over.category, over.requestsUsed, over.bytesUsed))
        assertEquals("BYTES", assertFailsWith<CaptureBudgetException> { budget.bytes(-1) }.category)
        budget.request()
        assertEquals(listOf("REQUESTS", 1, 11L), assertFailsWith<CaptureBudgetException> { budget.request() }.let { listOf(it.category, it.requestsUsed, it.bytesUsed) })
    }

    @Test
    fun `the first caller fixes the deadline and later callers cannot move it`() {
        val budget = CaptureBudget(maxRequests = 1, maxBytes = 1)
        runConcurrently(4) { index -> budget.startDeadline(1_000L * (index + 1), Duration.ofSeconds(1)) }
        val remaining = budget.remainingNanos(0)
        assertEquals(true, remaining in (1_000_000_000L + 1_000)..(1_000_000_000L + 4_000))
        budget.startDeadline(999, Duration.ofDays(1))
        assertEquals(remaining, budget.remainingNanos(0))
        assertEquals("DEADLINE", assertFailsWith<CaptureBudgetException> { budget.checkDeadline(remaining + 1) }.category)
    }

    @Test
    fun `measurement counters are exact under concurrency`() {
        val budget = CaptureBudget(maxRequests = 1, maxBytes = 1)
        runConcurrently(6) { repeat(500) { budget.counters.cacheHit(); budget.counters.rateLimited(); budget.counters.retry() } }
        assertEquals(listOf(3000, 3000, 3000), listOf(budget.counters.cacheHits, budget.counters.rateLimitedResponses, budget.counters.retries))
    }
}
