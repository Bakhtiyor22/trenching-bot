package com.trenching.bot.marketdata

import com.trenching.bot.chaincapture.CaptureCheckpoint
import com.trenching.bot.chaincapture.CaptureCheckpointObserver
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.Sha256
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.application.MarketHistoryCaptureService
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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

class MarketHistoryParallelCaptureTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `a capture at concurrency 4 writes the same manifest bytes and artifacts as concurrency 1 across several checkpoint batches`() {
        val sequential = Chain(130).also { capture(it, directory.resolve("one"), concurrency = 1) }
        val parallel = Chain(130).also { capture(it, directory.resolve("four"), concurrency = 4) }

        assertEquals(artifactHashes(directory.resolve("one")), artifactHashes(directory.resolve("four")))
        assertEquals(130, artifactHashes(directory.resolve("four")).size)
        assertEquals(manifestBytes(directory.resolve("one")).toList(), manifestBytes(directory.resolve("four")).toList())
        val records = recorded().inspect(directory.resolve("four")).manifest.records
        assertEquals((1..130).map(::signature), records.map { it.signature })
        assertTrue(parallel.peakInFlight in 2..4, "peak in flight ${parallel.peakInFlight}")
        assertEquals(1, sequential.peakInFlight)
    }

    @Test
    fun `an interrupted capture resumed later fetches only unresolved records and ends as the uninterrupted capture`() {
        val reference = directory.resolve("reference")
        capture(Chain(60), reference, concurrency = 1)

        val interrupted = directory.resolve("interrupted")
        val chain = Chain(60).apply { failFromRequest = 25 }
        val first = capture(chain, interrupted, concurrency = 4)
        assertEquals("HTTP_ERROR", first.stopReason)
        val recordedAfterInterruption = first.manifest.records.filter { it.artifact != null }.map { it.signature }.toSet()
        assertTrue(recordedAfterInterruption.size >= 24, "recorded ${recordedAfterInterruption.size}")
        assertEquals(recordedAfterInterruption, recorded().inspect(interrupted).manifest.records.filter { it.artifact != null }.map { it.signature }.toSet())
        assertTrue(first.manifest.records.any { it.outcome == MarketTransactionOutcome.PROVIDER_UNAVAILABLE })

        val resumedChain = Chain(60)
        val resumed = capture(resumedChain, interrupted, concurrency = 4)
        assertNull(resumed.stopReason)
        assertEquals(emptySet(), resumedChain.started.toSet().intersect(recordedAfterInterruption))
        assertEquals(60 - recordedAfterInterruption.size, resumedChain.started.size)
        assertEquals(artifactHashes(reference), artifactHashes(interrupted))
        val expected = recorded().inspect(reference).manifest
        assertEquals(expected.copy(requestsUsed = 0, bytesUsed = 0), resumed.manifest.copy(requestsUsed = 0, bytesUsed = 0))
    }

    @Test
    fun `a stop requested during the details starts no further request and saves what completed`() {
        val observer = StopOnDemand()
        val chain = Chain(40).apply { onStart = { number -> if (number == 7) observer.stop = true } }

        assertFailsWith<StopRequested> { capture(chain, directory, concurrency = 1, observer = observer) }

        assertEquals(7, chain.started.size)
        val saved = recorded().inspect(directory).manifest
        assertEquals((34..40).map(::signature), saved.records.filter { it.artifact != null }.map { it.signature })
        assertEquals(7, saved.records.count { it.outcome == MarketTransactionOutcome.CAPTURED })
    }

    @Test
    fun `a stop requested with requests in flight lets them finish, saves them and then starts no request`() {
        val observer = StopOnDemand()
        val chain = Chain(60).apply { onStart = { number -> if (number == 10) observer.stop = true } }

        assertFailsWith<StopRequested> { capture(chain, directory, concurrency = 4, observer = observer) }

        assertTrue(chain.started.size in 10..13, "started ${chain.started.size}")
        val saved = recorded().inspect(directory).manifest
        assertEquals(chain.started.size, saved.records.count { it.outcome == MarketTransactionOutcome.CAPTURED })
        assertEquals(saved.records.filter { it.artifact != null }.map { it.signature }.toSet(), chain.started.toSet())
        val resumedChain = Chain(60)
        capture(resumedChain, directory, concurrency = 4)
        assertEquals(emptySet(), resumedChain.started.toSet().intersect(chain.started.toSet()))
        assertEquals(60, resumedChain.started.size + chain.started.size)
    }

    @Test
    fun `a request budget stop under concurrency records every completed artifact and counts the requests actually sent`() {
        val chain = Chain(50)
        val result = capture(chain, directory, concurrency = 4, requestBudget = 12)

        assertEquals("REQUESTS", result.stopReason)
        assertEquals(10, chain.started.size)
        assertEquals(chain.started.toSet(), result.manifest.records.filter { it.artifact != null }.map { it.signature }.toSet())
        assertEquals(12, result.manifest.requestsUsed)
        assertEquals(result.manifest, recorded().inspect(directory).manifest)
        assertEquals(10, Files.list(directory.resolve("transactions")).use { it.count() }.toInt())
    }

    @Test
    fun `a decided capture at concurrency 4 writes the same manifest bytes as at concurrency 1 and fetches at most one batch past the decision`() {
        val sequential = Chain(250).also { capture(it, directory.resolve("one"), concurrency = 1, cutoff = DecideAtSlot(DECISION_SLOT)) }
        val parallel = Chain(250).also { capture(it, directory.resolve("four"), concurrency = 4, cutoff = DecideAtSlot(DECISION_SLOT)) }

        assertEquals(manifestBytes(directory.resolve("one")).toList(), manifestBytes(directory.resolve("four")).toList())
        val manifest = recorded().inspect(directory.resolve("four")).manifest
        assertEquals(DECISION_SLOT, manifest.windows.single().cutoff?.throughSlot)
        assertEquals(3, manifest.schemaVersion)
        assertTrue(manifest.records.filter { it.slot > DECISION_SLOT }.all { it.outcome == MarketTransactionOutcome.NOT_NEEDED && it.artifact == null })
        assertTrue(manifest.records.filter { it.slot <= DECISION_SLOT }.all { it.outcome == MarketTransactionOutcome.CAPTURED })
        listOf(sequential, parallel).forEach { chain ->
            val slots = chain.started.map { slot(indexOf(it)) }
            assertTrue(slots.containsAll((1750L..DECISION_SLOT).toList()))
            assertTrue(slots.count { it > DECISION_SLOT } <= 99, "fetched past the decision ${slots.count { it > DECISION_SLOT }}")
        }
        assertEquals((1750L..1849L).toList(), sequential.started.map { slot(indexOf(it)) })
    }

    @Test
    fun `an interrupted decided capture resumed later ends as the uninterrupted decided capture`() {
        val reference = capture(Chain(250), directory.resolve("reference"), concurrency = 1, cutoff = DecideAtSlot(DECISION_SLOT)).manifest
        val interrupted = directory.resolve("interrupted")
        val first = capture(Chain(250).apply { failFromRequest = 30 }, interrupted, concurrency = 4, cutoff = DecideAtSlot(DECISION_SLOT))
        assertEquals("HTTP_ERROR", first.stopReason)
        assertNull(first.manifest.windows.single().cutoff)

        val resumedChain = Chain(250)
        val resumed = capture(resumedChain, interrupted, concurrency = 4, cutoff = DecideAtSlot(DECISION_SLOT))

        assertNull(resumed.stopReason)
        assertEquals(emptySet(), resumedChain.started.toSet().intersect(first.manifest.records.filter { it.artifact != null }.map { it.signature }.toSet()))
        assertEquals(reference.copy(requestsUsed = 0, bytesUsed = 0), resumed.manifest.copy(requestsUsed = 0, bytesUsed = 0))
    }

    @Test
    fun `a transaction shared by two windows is fetched once at concurrency 4`() {
        val chain = Chain(0).apply { listing = { address -> if (address == MINT) listOf(1, 2, 3, 4) else listOf(4, 5) } }
        val windows = listOf(window(MINT), window(OTHER_MINT))

        val result = capture(chain, directory, concurrency = 4, windows = windows, maxTransactions = 5)

        assertEquals(listOf(1, 2, 3, 4, 5).map(::signature).sorted(), chain.started.sorted())
        assertEquals(1, chain.started.count { it == signature(4) })
        assertTrue(result.manifest.complete)
    }

    private fun capture(
        chain: Chain,
        target: Path,
        concurrency: Int,
        observer: CaptureCheckpointObserver = CaptureCheckpointObserver.NONE,
        requestBudget: Int = 1000,
        windows: List<MarketCaptureWindow> = listOf(window(MINT)),
        maxTransactions: Int = 300,
        cutoff: MarketCaptureCutoff? = null,
    ): MarketHistoryCaptureResult = MarketHistoryCaptureService(chain.rpc(), MarketHistoryArtifactStore(mapper), CLOCK, concurrency, observer)
        .capture(MarketHistoryCaptureRequest(target, windows, PARAMETERS, 20, maxTransactions, requestBudget, 104_857_600,
            cutoffMode = if (cutoff == null) MarketCutoffMode.FULL else MarketCutoffMode.DECIDED, cutoff = cutoff,
            cutoffIdentity = cutoff?.let { IDENTITY }))

    private fun artifactHashes(root: Path): Map<String, String> = Files.list(root.resolve("transactions")).use { paths ->
        paths.toList().associate { it.fileName.toString() to Sha256.hex(Files.readAllBytes(it)) }
    }

    private fun manifestBytes(target: Path): ByteArray = Files.readAllBytes(target.resolve("market-manifest.json"))

    private fun recorded(): RecordedMarketHistory = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)

    private fun window(mint: String) = MarketCaptureWindow.of(MintAddress.of(mint), Slot.of(1001), Slot.of(2000), TransactionSignature.of(signature(SOURCE)))

    private class StopRequested : RuntimeException()

    private class StopOnDemand : CaptureCheckpointObserver {
        @Volatile var stop = false

        override fun checkpoint(checkpoint: CaptureCheckpoint) { if (stop) throw StopRequested() }

        override fun stopRequested(): Boolean = stop
    }

    /** A thread-safe fake chain: entry `i` is `signature(i)` at slot `2000 - i`; replies take longer for earlier entries so completion order inverts. */
    private inner class Chain(count: Int) {
        val started = CopyOnWriteArrayList<String>()
        var listing: (String) -> List<Int> = { (1..count).toList() }
        var failFromRequest = Int.MAX_VALUE
        var onStart: (Int) -> Unit = {}
        private val inFlight = AtomicInteger()
        private val peak = AtomicInteger()
        private val requests = AtomicInteger()
        val peakInFlight: Int get() = peak.get()

        fun rpc() = SolanaReadRpc { method, params, budget ->
            budget.request()
            when (method) {
                "getGenesisHash" -> mapper.readTree("\"$GENESIS\"")
                "getSignaturesForAddress" -> mapper.readTree(listing(params[0] as String).joinToString(",", "[", "]") { entry(it) })
                "getTransaction" -> transaction(params[0] as String)
                else -> error("Unexpected method $method")
            }
        }

        private fun transaction(signature: String): JsonNode {
            val number = requests.incrementAndGet()
            started += signature
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                onStart(number)
                if (number >= failFromRequest) throw CaptureProviderException("HTTP_ERROR")
                Thread.sleep(((number % 5) * 3).toLong())
                return mapper.readTree(transactionJson(signature, slot(indexOf(signature))))
            } finally { inFlight.decrementAndGet() }
        }

        private fun entry(index: Int) =
            """{"signature":"${signature(index)}","slot":${slot(index)},"err":null,"memo":null,"blockTime":1791049629}"""
    }

    private fun slot(index: Int): Long = 2000L - index

    private val indexes = (1..MAX_INDEX).associateBy(::signature)

    private fun indexOf(signature: String): Int = indexes.getValue(signature)

    private fun transactionJson(signature: String, slot: Long) =
        """{"slot":$slot,"blockTime":1791049629,"version":1,"meta":{"err":null,"fee":5000,"preBalances":[1000000],"postBalances":[995000],"logMessages":[]},""" +
            """"transaction":{"signatures":["$signature"],"message":{"accountKeys":[{"pubkey":"$OTHER_MINT","signer":true,"writable":true}],""" +
            """"instructions":[{"programId":"$OTHER_MINT","accounts":[],"data":""}],"recentBlockhash":"$OTHER_MINT"}}}"""

    /** Hash-derived, so no two signatures differ only in letter case and collide as artifact files on a case-insensitive file system. */
    private fun signature(index: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return Base58.encode(digest.digest("a$index".toByteArray()) + digest.digest("b$index".toByteArray()))
    }

    private companion object {
        const val GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val OTHER_MINT = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        const val SOURCE = 77_777
        const val MAX_INDEX = 300
        const val DECISION_SLOT = 1800L
        val IDENTITY = CutoffIdentity("engine-v1", "a".repeat(64))
        val PARAMETERS = MarketCaptureWindowParameters(horizonSlots = 4500, maxAgeSlots = 2)
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC)
    }
}
