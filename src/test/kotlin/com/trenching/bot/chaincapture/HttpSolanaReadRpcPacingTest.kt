package com.trenching.bot.chaincapture

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class HttpSolanaReadRpcPacingTest {
    private val timing = ManualTiming()

    private fun rpc(server: LocalRpcServer, limiter: ProviderRequestLimiter, timing: CaptureTiming = this.timing) =
        HttpSolanaReadRpc(TestSettings(endpoint = server.endpoint), testMapper, testClock, timing, limiter)

    private fun call(rpc: HttpSolanaReadRpc, budget: CaptureBudget = CaptureBudget(10, 10_000)) =
        rpc.call("getGenesisHash", emptyList(), budget)

    @Test
    fun `two transports sharing a limiter never start closer than the interval`() {
        LocalRpcServer { LocalRpcServer.reply(it, 200, GENESIS_REPLY) }.use { server ->
            val limiter = ProviderRequestLimiter(4, Duration.ofMillis(125), timing)
            val first = rpc(server, limiter)
            val second = rpc(server, limiter)
            repeat(2) { call(first); call(second) }
            assertEquals(List(3) { Duration.ofMillis(125) }, timing.pauses)
        }
    }

    @Test
    fun `transports sharing a limiter together stay within its in flight limit`() {
        val running = AtomicInteger()
        val highest = AtomicInteger()
        LocalRpcServer { exchange ->
            highest.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            Thread.sleep(80)
            running.decrementAndGet()
            LocalRpcServer.reply(exchange, 200, GENESIS_REPLY)
        }.use { server ->
            val limiter = ProviderRequestLimiter(2, Duration.ofMillis(1))
            val transports = listOf(rpc(server, limiter, SystemCaptureTiming), rpc(server, limiter, SystemCaptureTiming))
            val budget = CaptureBudget(100, 100_000)
            runConcurrently(8) { index -> call(transports[index % 2], budget) }
            assertEquals(2, highest.get())
            assertEquals(8, budget.requestsUsed)
        }
    }

    @Test
    fun `a rate limit seen by one worker keeps every other worker from starting until the retry time`() {
        val limited = CountDownLatch(1)
        val arrivals = CopyOnWriteArrayList<Long>()
        val requests = AtomicInteger()
        val rateLimitedAtNanos = AtomicLong()
        LocalRpcServer { exchange ->
            if (requests.getAndIncrement() == 0) {
                rateLimitedAtNanos.set(System.nanoTime())
                limited.countDown()
                LocalRpcServer.reply(exchange, 429, "{}", mapOf("Retry-After" to "1"))
            } else {
                arrivals += System.nanoTime()
                LocalRpcServer.reply(exchange, 200, GENESIS_REPLY)
            }
        }.use { server ->
            val holdBegun = CountDownLatch(1)
            val observing = object : CaptureTiming by SystemCaptureTiming {
                override fun pause(duration: Duration) {
                    if (duration >= Duration.ofMillis(500)) holdBegun.countDown()
                    SystemCaptureTiming.pause(duration)
                }
            }
            val limiter = ProviderRequestLimiter(4, Duration.ofMillis(1), observing)
            val worker = rpc(server, limiter, SystemCaptureTiming)
            val other = rpc(server, limiter, SystemCaptureTiming)
            val first = Thread { call(worker) }.apply { start() }
            assertTrue(limited.await(5, TimeUnit.SECONDS))
            // The limited worker only sleeps through the hold once the hold is recorded, so waiting for that sleep needs no guessed delay.
            assertTrue(holdBegun.await(5, TimeUnit.SECONDS))
            call(other)
            first.join()
            assertEquals(2, arrivals.size)
            val earliest = arrivals.min()
            assertTrue(earliest - rateLimitedAtNanos.get() >= Duration.ofMillis(900).toNanos(), "a worker started during the pause")
        }
    }

    @Test
    fun `many concurrent calls through two transports sharing a limiter keep the interval between starts and the in flight limit`() {
        val interval = Duration.ofMillis(100)
        val tolerance = Duration.ofMillis(40)
        val inFlight = AtomicInteger()
        val highest = AtomicInteger()
        val arrivals = CopyOnWriteArrayList<Long>()
        LocalRpcServer { exchange ->
            arrivals += System.nanoTime()
            highest.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            Thread.sleep(400)
            inFlight.decrementAndGet()
            LocalRpcServer.reply(exchange, 200, GENESIS_REPLY)
        }.use { server ->
            val limiter = ProviderRequestLimiter(3, interval)
            val transports = listOf(rpc(server, limiter, SystemCaptureTiming), rpc(server, limiter, SystemCaptureTiming))
            transports.forEach { call(it) }
            arrivals.clear()
            highest.set(0)
            val budget = CaptureBudget(100, 1_000_000)
            runConcurrently(18) { index -> call(transports[index % 2], budget) }
            assertEquals(18, arrivals.size)
            assertEquals(3, highest.get(), "the in flight limit must be reached and never exceeded")
            val gaps = arrivals.sorted().zipWithNext { earlier, later -> later - earlier }
            assertTrue(gaps.all { it >= (interval - tolerance).toNanos() }, "starts came closer than the interval: ${gaps.map { Duration.ofNanos(it).toMillis() }}")
        }
    }

    @Test
    fun `rate limited responses are cooperative pauses that count as retries and still stop at the attempt cap`() {
        val requests = AtomicInteger()
        LocalRpcServer { requests.incrementAndGet(); LocalRpcServer.reply(it, 429, "{}") }.use { server ->
            val budget = CaptureBudget(10, 10_000)
            val failure = assertFailsWith<CaptureProviderException> { call(rpc(server, ProviderRequestLimiter(1, Duration.ofMillis(500), timing)), budget) }
            assertEquals("RATE_LIMITED", failure.category)
            assertEquals(3, requests.get())
            assertEquals(3, budget.requestsUsed)
            assertEquals(listOf(3, 2), listOf(budget.counters.rateLimitedResponses, budget.counters.retries))
            assertEquals(listOf(Duration.ofMillis(500), Duration.ofMillis(1000)), timing.pauses)
        }
    }

    @Test
    fun `an RPC rate limit error is retried like HTTP 429 and recorded as rate limited`() {
        val requests = AtomicInteger()
        LocalRpcServer { exchange ->
            val body = if (requests.getAndIncrement() == 0) """{"jsonrpc":"2.0","id":1,"error":{"code":-32005,"message":"busy"}}""" else GENESIS_REPLY
            LocalRpcServer.reply(exchange, 200, body)
        }.use { server ->
            val budget = CaptureBudget(10, 10_000)
            assertEquals("genesis", call(rpc(server, ProviderRequestLimiter(1, Duration.ofMillis(500), timing)), budget).asString())
            assertEquals(listOf(2, 1, 1), listOf(budget.requestsUsed, budget.counters.rateLimitedResponses, budget.counters.retries))
            assertEquals(listOf(Duration.ofMillis(500)), timing.pauses)
        }
    }

    @Test
    fun `server errors back off only the failing worker and are not counted as rate limits`() {
        LocalRpcServer { LocalRpcServer.reply(it, 503, "{}") }.use { server ->
            val limiter = ProviderRequestLimiter(1, Duration.ofMillis(125), timing)
            val budget = CaptureBudget(10, 10_000)
            assertFailsWith<CaptureProviderException> { call(rpc(server, limiter), budget) }
            assertEquals(listOf(3, 0, 2), listOf(budget.requestsUsed, budget.counters.rateLimitedResponses, budget.counters.retries))
        }
    }
}
