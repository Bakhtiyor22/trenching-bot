package com.trenching.bot.chaincapture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class BoundedParallelFetchTest {
    private val fetcher = BoundedParallelFetch()
    private val budget = CaptureBudget(maxRequests = 1000, maxBytes = 1_000_000)

    private fun fetchThreads(): List<Thread> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            val alive = Thread.getAllStackTraces().keys.filter { it.name.startsWith(BoundedParallelFetch.THREAD_PREFIX) && it.isAlive }
            if (alive.isEmpty() || System.nanoTime() > deadline) return alive
            Thread.sleep(10)
        }
    }

    @Test
    fun `returns results in input order even when later items finish first`() {
        val items = (0 until 8).toList()
        val result = fetcher.fetch(items, concurrency = 4, budget = budget, stopRequested = { false }) { item ->
            Thread.sleep((8 - item) * 15L)
            "result-$item"
        }
        assertEquals(items, result.completed.map { it.index })
        assertEquals(items, result.completed.map { it.item })
        assertEquals(items.map { "result-$it" }, result.completed.map { it.result })
        assertNull(result.failure)
        assertFalse(result.stopped)
        assertTrue(result.finished)
    }

    @Test
    fun `never runs more fetches at once than the concurrency`() {
        val running = AtomicInteger()
        val highest = AtomicInteger()
        val result = fetcher.fetch((1..12).toList(), 3, budget, { false }) {
            highest.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            Thread.sleep(40)
            running.decrementAndGet()
        }
        assertEquals(12, result.completed.size)
        assertEquals(3, highest.get())
    }

    @Test
    fun `a stop request halts new starts and keeps what finished`() {
        val started = AtomicInteger()
        val result = fetcher.fetch((0 until 10).toList(), 1, budget, { started.get() >= 3 }) { started.incrementAndGet(); it }
        assertEquals(listOf(0, 1, 2), result.completed.map { it.result })
        assertTrue(result.stopped)
        assertFalse(result.finished)
        assertNull(result.failure)
    }

    @Test
    fun `a stop requested while waiting for a free slot prevents the next start`() {
        val stop = AtomicBoolean()
        val firstRunning = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = AtomicInteger()
        val worker = Thread {
            val result = fetcher.fetch(listOf(0, 1, 2), 1, budget, { stop.get() }) { item ->
                started.incrementAndGet()
                if (item == 0) { firstRunning.countDown(); release.await(5, TimeUnit.SECONDS) }
                item
            }
            assertEquals(listOf(0), result.completed.map { it.result })
            assertTrue(result.stopped)
        }
        worker.start()
        assertTrue(firstRunning.await(5, TimeUnit.SECONDS))
        stop.set(true)
        release.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertEquals(1, started.get())
    }

    @Test
    fun `a fatal error stops new starts and returns the completed items with that error`() {
        val failure = CaptureBudgetException("REQUESTS")
        val result = fetcher.fetch((0 until 10).toList(), 1, budget, { false }) { item -> if (item == 2) throw failure else item }
        assertEquals(listOf(0, 1), result.completed.map { it.result })
        assertSame(failure, result.failure)
        assertFalse(result.stopped)
        assertFalse(result.finished)
    }

    @Test
    fun `in flight items finish after a fatal error and are returned in input order`() {
        val failure = CaptureProviderException("AUTHENTICATION")
        val failed = CountDownLatch(1)
        // Item 1 fails only once item 2 runs, so all three slots are in flight when the run turns fatal.
        val secondInFlight = CountDownLatch(1)
        val result = fetcher.fetch((0 until 10).toList(), 3, budget, { false }) { item ->
            when (item) {
                0 -> { failed.await(5, TimeUnit.SECONDS); Thread.sleep(50); item }
                1 -> { secondInFlight.await(5, TimeUnit.SECONDS); failed.countDown(); throw failure }
                else -> { secondInFlight.countDown(); failed.await(5, TimeUnit.SECONDS); item }
            }
        }
        assertSame(failure, result.failure)
        assertEquals(listOf(0, 2), result.completed.map { it.index })
    }

    @Test
    fun `leaves no worker threads behind after a normal run, a stop or a failure`() {
        fetcher.fetch((0 until 6).toList(), 4, budget, { false }) { it }
        fetcher.fetch((0 until 6).toList(), 4, budget, { true }) { it }
        fetcher.fetch((0 until 6).toList(), 4, budget, { false }) { if (it == 3) throw CaptureBudgetException("DEADLINE") else it }
        assertTrue(fetchThreads().isEmpty(), fetchThreads().toString())
    }

    @Test
    fun `an error that is not an exception is rethrown after the workers are cleaned up`() {
        assertFailsWith<OutOfMemoryError> { fetcher.fetch(listOf(1, 2), 2, budget, { false }) { throw OutOfMemoryError("simulated") } }
        assertTrue(fetchThreads().isEmpty())
    }

    @Test
    fun `when several items fail the lowest numbered failure is reported whatever order they failed in`() {
        val low = CaptureProviderException("UNAVAILABLE")
        val high = CaptureBudgetException("REQUESTS")
        val highFailed = CountDownLatch(1)
        val result = fetcher.fetch(listOf(0, 1), 2, budget, { false }) { item ->
            if (item == 1) { highFailed.countDown(); throw high }
            highFailed.await(5, TimeUnit.SECONDS)
            throw low
        }
        assertSame(low, result.failure)
        assertTrue(result.completed.isEmpty())
    }

    @Test
    fun `an error at a higher index is rethrown in preference to an exception at a lower one`() {
        val error = OutOfMemoryError("simulated")
        val errorThrown = CountDownLatch(1)
        val thrown = assertFailsWith<OutOfMemoryError> {
            fetcher.fetch(listOf(0, 1), 2, budget, { false }) { item ->
                if (item == 1) { errorThrown.countDown(); throw error }
                errorThrown.await(5, TimeUnit.SECONDS)
                throw CaptureProviderException("UNAVAILABLE")
            }
        }
        assertSame(error, thrown)
        assertTrue(fetchThreads().isEmpty())
    }

    /** Runs [fetch] on its own thread, which the test interrupts once [ready] has been counted down by a fetch. */
    private fun interruptedRun(items: List<Int>, concurrency: Int, ready: CountDownLatch, release: CountDownLatch): Pair<ParallelFetchResult<Int, Int>, Boolean> {
        var outcome: ParallelFetchResult<Int, Int>? = null
        var flag = false
        var crash: Throwable? = null
        val caller = Thread {
            try {
                outcome = fetcher.fetch(items, concurrency, budget, { false }) { item ->
                    ready.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    item
                }
            } catch (throwable: Throwable) { crash = throwable }
            flag = Thread.currentThread().isInterrupted
        }
        caller.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        caller.interrupt()
        caller.join(10_000)
        assertFalse(caller.isAlive)
        release.countDown()
        return checkNotNull(outcome) { "the fetch crashed: $crash" } to flag
    }

    @Test
    fun `a caller interrupted while waiting for a free slot gets an interruption failure, its workers are stopped and the flag is kept`() {
        val (result, flag) = interruptedRun((0 until 3).toList(), 1, CountDownLatch(1), CountDownLatch(1))
        val failure = assertIs<CaptureProviderException>(result.failure)
        assertEquals("INTERRUPTED", failure.category)
        assertTrue(result.completed.isEmpty())
        assertFalse(result.finished)
        assertTrue(flag, "the interrupt flag must be restored")
        assertTrue(fetchThreads().isEmpty(), fetchThreads().toString())
    }

    @Test
    fun `a caller interrupted while the last fetches run gets an interruption failure and no worker is left behind`() {
        val (result, flag) = interruptedRun(listOf(0, 1), 2, CountDownLatch(2), CountDownLatch(1))
        assertEquals("INTERRUPTED", assertIs<CaptureProviderException>(result.failure).category)
        assertTrue(flag, "the interrupt flag must be restored")
        assertTrue(fetchThreads().isEmpty(), fetchThreads().toString())
    }

    @Test
    fun `a stop check that throws is a fatal failure and keeps the fetches already completed`() {
        val broken = IllegalStateException("stop check failed")
        val started = AtomicInteger()
        val result = fetcher.fetch((0 until 6).toList(), 1, budget, { if (started.get() >= 2) throw broken else false }) { started.incrementAndGet(); it }
        assertSame(broken, result.failure)
        assertEquals(listOf(0, 1), result.completed.map { it.result })
        assertFalse(result.stopped)
        assertFalse(result.finished)
        assertTrue(fetchThreads().isEmpty())
    }

    @Test
    fun `reports only the work of this run`() {
        budget.request()
        budget.counters.cacheHit()
        val result = fetcher.fetch((0 until 5).toList(), 2, budget, { false }) { item ->
            budget.request()
            if (item % 2 == 0) budget.counters.cacheHit() else { budget.counters.rateLimited(); budget.counters.retry(); budget.counters.retry() }
        }
        val measurements = result.measurements
        assertEquals(listOf(5, 3, 2, 4), listOf(measurements.requestsSent, measurements.cacheHits, measurements.rateLimitedResponses, measurements.retries))
        assertTrue(!measurements.elapsed.isNegative)
    }

    @Test
    fun `an empty input completes immediately`() {
        val result = fetcher.fetch(emptyList<Int>(), 4, budget, { false }) { it }
        assertTrue(result.completed.isEmpty() && result.finished && result.failure == null)
    }
}
