package com.trenching.bot.console

import com.trenching.bot.console.application.SolanaProbeService
import com.trenching.bot.console.solana.AddressCheckOutcome
import com.trenching.bot.console.solana.FakeSolanaProbe
import com.trenching.bot.console.solana.InvalidSolanaAddressException
import com.trenching.bot.console.solana.MutableClock
import com.trenching.bot.console.solana.ProbeOutcome
import com.trenching.bot.console.solana.SolanaCluster
import com.trenching.bot.console.solana.SolanaNotConfiguredException
import com.trenching.bot.console.solana.SolanaRpcTestData
import com.trenching.bot.console.solana.SolanaRpcTestData.TOKEN_MINT
import com.trenching.bot.console.solana.SolanaRpcTestData.TRACKED_WALLET
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

@ExtendWith(OutputCaptureExtension::class)
class SolanaProbeServiceTest {
    private val clock = MutableClock(Instant.parse("2026-10-02T12:00:00Z"))
    private val meterRegistry = SimpleMeterRegistry()
    private val ok = ProbeOutcome.Ok(latencyMs = 42, version = "3.0.6", slot = 412_345_678, cluster = SolanaCluster.DEVNET)

    private fun service(probe: FakeSolanaProbe) =
        SolanaProbeService(probe, SolanaRpcTestData.properties(SolanaRpcTestData.RPC_URL), clock, meterRegistry)

    @Test
    fun `returns the cached result within the ttl without probing again`() {
        val probe = FakeSolanaProbe(host = "rpc.example.test", outcome = ProbeOutcome.RateLimited)
        val service = service(probe)

        val first = service.check()
        clock.advance(Duration.ofMillis(29_999))
        val second = service.check()

        assertEquals(1, probe.checkCount)
        assertFalse(first.cached)
        assertTrue(second.cached)
        assertEquals(ProbeOutcome.RateLimited, second.outcome)
        assertEquals(first.checkedAt, second.checkedAt)
    }

    @Test
    fun `probes again once the ttl has elapsed`() {
        val probe = FakeSolanaProbe(host = "rpc.example.test", outcome = ok)
        val service = service(probe)

        service.check()
        clock.advance(Duration.ofSeconds(30))
        val second = service.check()

        assertEquals(2, probe.checkCount)
        assertFalse(second.cached)
        assertEquals(Instant.parse("2026-10-02T12:00:30Z"), second.checkedAt)
    }

    @Test
    fun `concurrent checks make one probe call`() {
        val probe = FakeSolanaProbe(host = "rpc.example.test", outcome = ok).apply { blockUntilReleased() }
        val service = service(probe)
        val callers = 4
        val pool = Executors.newFixedThreadPool(callers)
        val threads = mutableListOf<Thread>()
        try {
            val results = (1..callers).map {
                pool.submit(Callable { synchronized(threads) { threads += Thread.currentThread() }; service.check() })
            }
            awaitAllParked(threads, callers)
            probe.release()

            val checks = results.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, probe.checkCount)
            assertEquals(1, checks.count { !it.cached })
            assertTrue(checks.all { it.outcome == ok })
        } finally {
            probe.release()
            pool.shutdownNow()
        }
    }

    @Test
    fun `status reports the host and the last result without probing`() {
        val probe = FakeSolanaProbe(host = "rpc.example.test", outcome = ok)
        val service = service(probe)

        val before = service.status()
        service.check()
        val after = service.status()

        assertEquals(1, probe.checkCount)
        assertTrue(before.configured)
        assertEquals("rpc.example.test", before.host)
        assertNull(before.lastCheck)
        assertEquals(ok, assertNotNull(after.lastCheck).outcome)
        assertTrue(assertNotNull(after.lastCheck).cached)
    }

    @Test
    fun `status reports an unconfigured rpc`() {
        val probe = FakeSolanaProbe(host = null)

        val status = service(probe).status()

        assertFalse(status.configured)
        assertNull(status.host)
        assertEquals(0, probe.checkCount)
    }

    @Test
    fun `records the probe timer tagged with the outcome and skips cached results`() {
        val service = service(FakeSolanaProbe(host = "rpc.example.test", outcome = ProbeOutcome.RateLimited))

        service.check()
        service.check()

        val timer = meterRegistry.find("trenching.solana.probe").tag("outcome", "rate_limited").timer()
        assertEquals(1L, assertNotNull(timer).count())
    }

    @Test
    fun `an unexpected probe failure degrades to unavailable`() {
        val probe = FakeSolanaProbe(host = "rpc.example.test").apply { failure = IllegalStateException("boom") }

        val check = service(probe).check()

        assertEquals(ProbeOutcome.Unavailable("unexpected error"), check.outcome)
    }

    @Test
    fun `unexpected failures retain diagnostics without logging unsafe exception chains`(output: CapturedOutput) {
        val unsafe = "https://rpc.example.test/?api-key=synthetic-credential"
        val error = IllegalStateException(unsafe, IllegalArgumentException(unsafe)).apply {
            addSuppressed(IllegalArgumentException(unsafe))
        }
        val probe = FakeSolanaProbe(host = "rpc.example.test").apply { failure = error }
        val service = service(probe)

        assertEquals(ProbeOutcome.Unavailable("unexpected error"), service.check().outcome)
        assertEquals(AddressCheckOutcome.Failed(ProbeOutcome.Unavailable("unexpected error")),
            service.checkAddress(TRACKED_WALLET).outcome)
        assertFalse(output.all.contains(unsafe))
        assertFalse(output.all.contains("synthetic-credential"))
        assertTrue(output.all.contains("IllegalStateException"))
        assertTrue(output.all.contains("SolanaProbeServiceTest"))
        assertTrue(output.all.contains("operation=check"))
        assertTrue(output.all.contains("operation=address_check"))
    }

    @Test
    fun `rejects an invalid address without probing`() {
        val probe = FakeSolanaProbe(host = "rpc.example.test")

        val error = assertFailsWith<InvalidSolanaAddressException> { service(probe).checkAddress("not-an-address") }

        assertEquals("not-an-address", error.address)
        assertEquals(0, probe.addressCheckCount)
    }

    @Test
    fun `rejects an address check when the rpc is not configured`() {
        val probe = FakeSolanaProbe(host = null)

        assertFailsWith<SolanaNotConfiguredException> { service(probe).checkAddress(TRACKED_WALLET) }
        assertEquals(0, probe.addressCheckCount)
    }

    @Test
    fun `caches the address check per address within the ttl`() {
        val probe = FakeSolanaProbe(host = "rpc.example.test",
            addressOutcome = AddressCheckOutcome.NoSuccessfulTransaction(signaturesExamined = 5))
        val service = service(probe)

        val first = service.checkAddress(TRACKED_WALLET)
        val repeated = service.checkAddress(TRACKED_WALLET)
        val other = service.checkAddress(TOKEN_MINT)

        assertEquals(2, probe.addressCheckCount)
        assertFalse(first.cached)
        assertTrue(repeated.cached)
        assertFalse(other.cached)
        assertEquals(TRACKED_WALLET, repeated.address.value)
    }

    private fun awaitAllParked(threads: List<Thread>, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val snapshot = synchronized(threads) { threads.toList() }
            if (snapshot.size == expected && snapshot.all { it.state == Thread.State.WAITING || it.state == Thread.State.TIMED_WAITING }) return
            Thread.onSpinWait()
        }
        throw AssertionError("callers did not all block within 10s")
    }
}
