package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.CaptureCheckpoint
import com.trenching.bot.chaincapture.CaptureCheckpointObserver
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.Sha256
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.Base58
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.application.WalletHistoryCaptureService
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class WalletHistoryParallelCaptureTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val store = WalletHistoryArtifactStore(mapper)

    @Test
    fun `a capture at concurrency 4 writes the same manifest bytes and artifacts as concurrency 1`() {
        val sequential = Chain(70).also { capture(it, directory.resolve("one"), concurrency = 1) }
        val parallel = Chain(70).also { capture(it, directory.resolve("four"), concurrency = 4) }

        assertEquals(artifactHashes(directory.resolve("one")), artifactHashes(directory.resolve("four")))
        assertEquals(70, artifactHashes(directory.resolve("four")).size)
        assertEquals(
            Files.readAllBytes(directory.resolve("one/manifest.json")).toList(),
            Files.readAllBytes(directory.resolve("four/manifest.json")).toList(),
        )
        assertEquals((1..70).map(::signature), store.inspect(directory.resolve("four")).manifest.records.map { it.signature })
        assertTrue(parallel.peakInFlight in 2..4, "peak in flight ${parallel.peakInFlight}")
        assertEquals(1, sequential.peakInFlight)
    }

    @Test
    fun `an interrupted capture resumed later fetches only unresolved records and ends as the uninterrupted capture`() {
        val reference = directory.resolve("reference")
        capture(Chain(50), reference, concurrency = 1)

        val interrupted = directory.resolve("interrupted")
        val first = capture(Chain(50).apply { failFromRequest = 21 }, interrupted, concurrency = 4)
        assertEquals("HTTP_ERROR", first.stopReason)
        val recordedAfterInterruption = first.manifest.records.filter { it.artifact != null }.map { it.signature }.toSet()
        assertEquals(20, recordedAfterInterruption.size)
        assertEquals(recordedAfterInterruption, store.inspect(interrupted).manifest.records.filter { it.artifact != null }.map { it.signature }.toSet())
        assertEquals(1, first.manifest.records.count { it.outcome == WalletHistoryOutcome.PROVIDER_UNAVAILABLE })

        val resumedChain = Chain(50)
        val resumed = capture(resumedChain, interrupted, concurrency = 4)
        assertNull(resumed.stopReason)
        assertEquals(emptySet(), resumedChain.started.toSet().intersect(recordedAfterInterruption))
        assertEquals(30, resumedChain.started.size)
        assertEquals(artifactHashes(reference), artifactHashes(interrupted))
        val expected = store.inspect(reference).manifest
        assertEquals(expected.copy(requestsUsed = 0, bytesUsed = 0), resumed.manifest.copy(requestsUsed = 0, bytesUsed = 0))
    }

    @Test
    fun `a stop requested during the details starts no further request and saves what completed`() {
        val observer = StopOnDemand()
        val chain = Chain(40).apply { onStart = { number -> if (number == 7) observer.stop = true } }

        assertFailsWith<StopRequested> { capture(chain, directory, concurrency = 1, observer = observer) }

        assertEquals(7, chain.started.size)
        val saved = store.inspect(directory).manifest
        assertEquals((1..7).map(::signature), saved.records.filter { it.artifact != null }.map { it.signature })
    }

    @Test
    fun `a stop requested with requests in flight lets them finish, saves them and then starts no request`() {
        val observer = StopOnDemand()
        val chain = Chain(60).apply { onStart = { number -> if (number == 10) observer.stop = true } }

        assertFailsWith<StopRequested> { capture(chain, directory, concurrency = 4, observer = observer) }

        assertTrue(chain.started.size in 10..13, "started ${chain.started.size}")
        val saved = store.inspect(directory).manifest
        assertEquals(chain.started.toSet(), saved.records.filter { it.artifact != null }.map { it.signature }.toSet())
        val resumedChain = Chain(60)
        capture(resumedChain, directory, concurrency = 4)
        assertEquals(emptySet(), resumedChain.started.toSet().intersect(chain.started.toSet()))
        assertEquals(60, resumedChain.started.size + chain.started.size)
    }

    @Test
    fun `a request budget stop under concurrency records every completed artifact and counts the requests actually sent`() {
        val chain = Chain(40)
        val result = capture(chain, directory, concurrency = 4, requestBudget = 12)

        assertEquals("REQUESTS", result.stopReason)
        assertEquals(10, chain.started.size)
        assertEquals(chain.started.toSet(), result.manifest.records.filter { it.artifact != null }.map { it.signature }.toSet())
        assertEquals(12, result.manifest.requestsUsed)
        assertEquals(result.manifest, store.inspect(directory).manifest)
        assertEquals(10, Files.list(directory.resolve("transactions")).use { it.count() }.toInt())
    }

    private fun capture(
        chain: Chain,
        target: Path,
        concurrency: Int,
        observer: CaptureCheckpointObserver = CaptureCheckpointObserver.NONE,
        requestBudget: Int = 1000,
    ): WalletHistoryCaptureResult = WalletHistoryCaptureService(chain.rpc(), mapper, CLOCK, concurrency, observer)
        .capture(WalletHistoryCaptureRequest(wallet, target, limit = 100, requestBudget = requestBudget))

    private fun artifactHashes(root: Path): Map<String, String> = Files.list(root.resolve("transactions")).use { paths ->
        paths.toList().associate { it.fileName.toString() to Sha256.hex(Files.readAllBytes(it)) }
    }

    private class StopRequested : RuntimeException()

    private class StopOnDemand : CaptureCheckpointObserver {
        @Volatile var stop = false

        override fun checkpoint(checkpoint: CaptureCheckpoint) { if (stop) throw StopRequested() }

        override fun stopRequested(): Boolean = stop
    }

    /** A thread-safe fake of the wallet's history: entry `i` is `signature(i)` at slot `10_000 - i`; earlier entries reply later, so completion order inverts. */
    private inner class Chain(private val count: Int) {
        val started = CopyOnWriteArrayList<String>()
        var failFromRequest = Int.MAX_VALUE
        var onStart: (Int) -> Unit = {}
        private val inFlight = AtomicInteger()
        private val peak = AtomicInteger()
        private val requests = AtomicInteger()
        private val indexOf = (1..count).associateBy(::signature)
        val peakInFlight: Int get() = peak.get()

        fun rpc() = SolanaReadRpc { method, params, budget ->
            budget.request()
            when (method) {
                "getGenesisHash" -> mapper.readTree("\"$GENESIS\"")
                "getSignaturesForAddress" -> mapper.readTree((1..count).joinToString(",", "[", "]") {
                    """{"signature":"${signature(it)}","slot":${slot(it)},"err":null}"""
                })
                "getTransaction" -> transaction(params[0] as String)
                else -> error("Unexpected method $method")
            }
        }

        private fun transaction(signature: String): JsonNode {
            val number = requests.incrementAndGet()
            started += signature
            peak.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                onStart(number)
                if (number >= failFromRequest) throw CaptureProviderException("HTTP_ERROR")
                Thread.sleep(((number % 5) * 3).toLong())
                return mapper.readTree(transactionJson(signature, slot(indexOf.getValue(signature))))
            } finally { inFlight.decrementAndGet() }
        }
    }

    private fun slot(index: Int): Long = 10_000L - index

    private fun transactionJson(signature: String, slot: Long) =
        """{"slot":$slot,"blockTime":1790985600,"version":0,"meta":{"err":null,"fee":5000,"preBalances":[1000000],"postBalances":[995000],"logMessages":[]},""" +
            """"transaction":{"signatures":["$signature"],"message":{"accountKeys":[{"pubkey":"${wallet.value}","signer":true,"writable":true}],""" +
            """"instructions":[{"programId":"${wallet.value}","accounts":[],"data":""}],"recentBlockhash":"${wallet.value}"}}}"""

    private fun signature(index: Int): String =
        Base58.encode(ByteArray(64).also { bytes -> bytes[62] = (index shr 8).toByte(); bytes[63] = index.toByte() })

    private val wallet = WalletAddress.of("1".repeat(32))

    private companion object {
        const val GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC)
    }
}
