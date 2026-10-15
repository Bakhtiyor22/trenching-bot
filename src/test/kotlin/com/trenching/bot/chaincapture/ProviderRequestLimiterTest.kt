package com.trenching.bot.chaincapture

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ProviderRequestLimiterTest {
    private val timing = ManualTiming()
    private fun budget() = CaptureBudget(maxRequests = 100, maxBytes = 1_000_000).also { it.startDeadline(timing.nanoTime(), Duration.ofMinutes(10)) }

    @Test
    fun `starts are spaced by the minimum interval from the first immediate start`() {
        val limiter = ProviderRequestLimiter(maxInFlight = 4, minInterval = Duration.ofMillis(125), timing = timing)
        val starts = (1..3).map { limiter.withStart(budget()) { timing.nanoTime() } }
        assertEquals(listOf(0L, 125_000_000L, 250_000_000L), starts)
    }

    @Test
    fun `never runs more actions at once than the in flight limit`() {
        val limiter = ProviderRequestLimiter(maxInFlight = 3, minInterval = Duration.ofMillis(1))
        val running = AtomicInteger()
        val highest = AtomicInteger()
        runConcurrently(12) {
            limiter.withStart(CaptureBudget(10, 1000)) {
                highest.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                Thread.sleep(60)
                running.decrementAndGet()
            }
        }
        assertEquals(3, highest.get())
        assertEquals(0, running.get())
    }

    @Test
    fun `a hold delays every later start on the limiter and a shorter hold never shortens it`() {
        val limiter = ProviderRequestLimiter(4, Duration.ofMillis(125), timing)
        limiter.holdStarts(Duration.ofSeconds(10), budget())
        limiter.holdStarts(Duration.ofSeconds(2), budget())
        val start = limiter.withStart(budget()) { timing.nanoTime() }
        assertEquals(Duration.ofSeconds(10).toNanos(), start)
        assertEquals(listOf(Duration.ofSeconds(10)), timing.pauses)
    }

    @Test
    fun `a hold longer than the caller deadline still binds other callers but ends the holder with a deadline stop`() {
        val limiter = ProviderRequestLimiter(4, Duration.ofMillis(125), timing)
        val short = CaptureBudget(10, 1000).also { it.startDeadline(timing.nanoTime(), Duration.ofMinutes(1)) }
        assertEquals("DEADLINE", assertFailsWith<CaptureBudgetException> { limiter.holdStarts(Duration.ofMinutes(5), short) }.category)
        assertEquals("DEADLINE", assertFailsWith<CaptureBudgetException> { limiter.withStart(short) { error("must not run") } }.category)
        assertTrue(timing.pauses.isEmpty())
        val start = limiter.withStart(budget()) { timing.nanoTime() }
        assertEquals(Duration.ofMinutes(5).toNanos(), start)
    }

    @Test
    fun `releases the permit when the action fails so the next caller is not blocked`() {
        val limiter = ProviderRequestLimiter(1, Duration.ofMillis(1), timing)
        assertFailsWith<IllegalStateException> { limiter.withStart(budget()) { error("boom") } }
        assertEquals("ok", limiter.withStart(budget()) { "ok" })
    }

    @Test
    fun `an interrupted wait is reported as an interruption, keeps the flag and releases the permit`() {
        var interrupt = true
        val interrupting = object : CaptureTiming by timing {
            override fun pause(duration: Duration) { if (interrupt) throw InterruptedException() else timing.pause(duration) }
        }
        val limiter = ProviderRequestLimiter(1, Duration.ofSeconds(1), interrupting)
        limiter.withStart(budget()) { }
        val failure = assertFailsWith<CaptureProviderException> { limiter.withStart(budget()) { error("must not run") } }
        assertEquals("INTERRUPTED", failure.category)
        assertTrue(Thread.interrupted())
        interrupt = false
        assertEquals("ok", limiter.withStart(budget()) { "ok" })
    }

    @Test
    fun `waiting for an in flight slot ends at the caller deadline`() {
        val limiter = ProviderRequestLimiter(1, Duration.ofMillis(1))
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread { limiter.withStart(CaptureBudget(10, 1000)) { holding.countDown(); release.await(5, TimeUnit.SECONDS) } }
        holder.start()
        try {
            assertTrue(holding.await(5, TimeUnit.SECONDS))
            val waiting = CaptureBudget(10, 1000).also { it.startDeadline(System.nanoTime(), Duration.ofMillis(50)) }
            assertEquals("DEADLINE", assertFailsWith<CaptureBudgetException> { limiter.withStart(waiting) { error("must not run") } }.category)
        } finally { release.countDown(); holder.join() }
    }

    @Test
    fun `limiters are built from validated settings`() {
        val limiter = ProviderRequestLimiter.of(TestSettings(maxConcurrentRequests = 2, minRequestInterval = Duration.ofMillis(40)), timing)
        val starts = (1..2).map { limiter.withStart(budget()) { timing.nanoTime() } }
        assertEquals(listOf(0L, 40_000_000L), starts)
        assertFalse(timing.pauses.isEmpty())
    }
}
