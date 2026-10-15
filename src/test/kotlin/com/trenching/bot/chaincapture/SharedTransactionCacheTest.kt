package com.trenching.bot.chaincapture

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory

class SharedTransactionCacheTest {
    @TempDir lateinit var directory: Path
    private val signature = "5".repeat(88)
    private val store = CaptureArtifactStore(testMapper)
    private val logs = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(SharedTransactionCache::class.java) as Logger

    @BeforeEach fun attachAppender() { logs.start(); logger.addAppender(logs) }
    @AfterEach fun detachAppender() { logger.detachAppender(logs) }

    private val transaction = validTransactionJson(signature)

    private fun cache() = SharedTransactionCache(directory.resolve("cache"), testMapper)

    /** Counts provider calls and answers getTransaction with [transaction], or with [answer] when given. */
    private class CountingRpc(private val answer: () -> JsonNode) : SolanaReadRpc {
        val calls = AtomicInteger()
        override fun call(method: String, params: List<Any>, budget: CaptureBudget): JsonNode {
            budget.request()
            calls.incrementAndGet()
            return answer()
        }
    }

    private fun capture(rpc: SolanaReadRpc, budget: CaptureBudget = CaptureBudget(10, 1_000_000)): JsonNode =
        ChainCaptureReads.transaction(CachingSolanaReadRpc(rpc, cache()), signature, budget)

    private fun cacheFiles(): List<Path> =
        if (Files.exists(directory.resolve("cache"))) Files.list(directory.resolve("cache")).use { it.filter { f -> f.fileName.toString().endsWith(".json") }.toList() } else emptyList()

    @Test
    fun `a second capture of a cached signature sends no request, spends no budget and writes identical artifact bytes`() {
        val provider = CountingRpc { exactJson(transaction) }
        val firstBudget = CaptureBudget(10, 1_000_000)
        val first = store.writeTransaction(directory.resolve("one"), signature, capture(provider, firstBudget))
        val secondBudget = CaptureBudget(10, 1_000_000)
        val second = store.writeTransaction(directory.resolve("two"), signature, capture(provider, secondBudget))
        assertEquals(1, provider.calls.get())
        assertEquals(listOf(1, 0, 0), listOf(firstBudget.requestsUsed, secondBudget.requestsUsed, firstBudget.counters.cacheHits))
        assertEquals(listOf(1L, 0L), listOf(secondBudget.counters.cacheHits.toLong(), secondBudget.bytesUsed))
        assertEquals(first.sha256, second.sha256)
        assertEquals(first.bytes, second.bytes)
        assertTrue(Files.readAllBytes(directory.resolve("one/transactions/$signature.json")).contentEquals(Files.readAllBytes(directory.resolve("two/transactions/$signature.json"))))
    }

    @Test
    fun `a corrupt entry is deleted, logged with its fields, refetched and replaced`() {
        val provider = CountingRpc { exactJson(transaction) }
        capture(provider)
        val file = cacheFiles().single()
        val corruptions = listOf<(Path) -> Unit>(
            { Files.writeString(it, Files.readString(it).take(40)) },
            { Files.writeString(it, Files.readString(it).replace("5000", "5001")) },
            { Files.writeString(it, Files.readString(it).replace(signature, "6".repeat(88))) },
            { Files.writeString(it, "") },
        )
        corruptions.forEachIndexed { index, corrupt ->
            corrupt(file)
            logs.list.clear()
            val before = provider.calls.get()
            val result = capture(provider)
            assertEquals(before + 1, provider.calls.get(), "corruption $index must refetch")
            assertEquals(5000, result.path("meta").path("fee").asInt())
            val warning = logs.list.single { it.level == Level.WARN }
            assertTrue(warning.formattedMessage.contains("signature=$signature"), warning.formattedMessage)
            assertTrue(warning.formattedMessage.contains("path="), warning.formattedMessage)
            assertEquals(1, cacheFiles().size)
            val healed = provider.calls.get()
            capture(provider)
            assertEquals(healed, provider.calls.get(), "the refetched entry must be served from the cache")
        }
    }

    @Test
    fun `a null transaction, a provider failure and a non object result are never cached`() {
        val missing = CountingRpc { JsonNodeFactory.instance.nullNode() }
        capture(missing)
        capture(missing)
        assertEquals(2, missing.calls.get())
        val failing = CountingRpc { throw CaptureProviderException("UNAVAILABLE") }
        repeat(2) { runCatching { capture(failing) } }
        assertEquals(2, failing.calls.get())
        assertTrue(cacheFiles().isEmpty())
    }

    @Test
    fun `a response that is not a valid transaction for its signature is never cached, so a retry fetches it again`() {
        val invalid = listOf(
            "no meta" to """{"slot":100,"blockTime":1790985600,"version":0,"transaction":{"signatures":["$signature"],"message":{}}}""",
            "another signature" to validTransactionJson("6".repeat(88)),
            "failed on chain" to validTransactionJson(signature).replace(""""err":null""", """"err":{"InstructionError":[0,"Custom"]}"""),
            "unsupported version" to validTransactionJson(signature, version = "99"),
            "unknown version" to validTransactionJson(signature, version = "\"v9\""),
        )
        invalid.forEach { (name, body) ->
            val provider = CountingRpc { exactJson(body) }
            repeat(2) { capture(provider) }
            assertEquals(2, provider.calls.get(), "$name must not be served from the cache")
            assertTrue(cacheFiles().isEmpty(), "$name must not be written")
        }
        val flaky = CountingRpc { exactJson("""{"slot":100}""") }
        capture(flaky)
        val healthy = CountingRpc { exactJson(transaction) }
        assertEquals(5000, capture(healthy).path("meta").path("fee").asInt())
        capture(healthy)
        assertEquals(1, healthy.calls.get())
        assertEquals(1, cacheFiles().size)
    }

    @Test
    fun `different request parameters never share an entry`() {
        val cache = cache()
        val json = listOf<Any>(signature, mapOf("encoding" to "json", "commitment" to "confirmed", "maxSupportedTransactionVersion" to 1))
        val parsed = listOf<Any>(signature, mapOf("encoding" to "jsonParsed", "commitment" to "confirmed", "maxSupportedTransactionVersion" to 1))
        val reordered = listOf<Any>(signature, mapOf("maxSupportedTransactionVersion" to 1, "commitment" to "confirmed", "encoding" to "jsonParsed"))
        val keyJson = assertNotNull(cache.keyFor(json))
        val keyParsed = assertNotNull(cache.keyFor(parsed))
        assertNotEquals(keyJson, keyParsed)
        assertEquals(keyParsed, cache.keyFor(reordered))
        assertNotEquals(keyParsed, cache.keyFor(listOf(signature, mapOf("encoding" to "jsonParsed", "commitment" to "finalized", "maxSupportedTransactionVersion" to 1))))
        assertNotEquals(keyParsed, cache.keyFor(listOf(signature, mapOf("encoding" to "jsonParsed", "commitment" to "confirmed", "maxSupportedTransactionVersion" to 0))))
        val provider = CountingRpc { exactJson(transaction) }
        val caching = CachingSolanaReadRpc(provider, cache)
        caching.call(ChainCaptureFormat.GET_TRANSACTION, json, CaptureBudget(10, 1000))
        caching.call(ChainCaptureFormat.GET_TRANSACTION, parsed, CaptureBudget(10, 1000))
        assertEquals(2, provider.calls.get())
        assertEquals(2, cacheFiles().size)
    }

    @Test
    fun `other methods and unsafe signatures pass straight through without touching the cache`() {
        val provider = CountingRpc { exactJson(transaction) }
        val caching = CachingSolanaReadRpc(provider, cache())
        repeat(2) { caching.call(ChainCaptureFormat.GET_GENESIS, emptyList(), CaptureBudget(10, 1000)) }
        repeat(2) { caching.call(ChainCaptureFormat.GET_TRANSACTION, listOf("../escape", mapOf("encoding" to "jsonParsed")), CaptureBudget(10, 1000)) }
        repeat(2) { caching.call(ChainCaptureFormat.GET_TRANSACTION, emptyList(), CaptureBudget(10, 1000)) }
        assertEquals(6, provider.calls.get())
        assertTrue(cacheFiles().isEmpty())
        assertFalse(Files.exists(directory.resolve("escape.json")))
    }

    @Test
    fun `a cache directory that is a symbolic link is refused`() {
        val real = Files.createDirectory(directory.resolve("real"))
        val link = Files.createSymbolicLink(directory.resolve("link"), real)
        val failure = runCatching { SharedTransactionCache(link, testMapper) }.exceptionOrNull()
        assertTrue(failure is CaptureValidationException, failure.toString())
    }

    @Test
    fun `a cache location that is a file is refused when the cache is created`() {
        Files.writeString(directory.resolve("cache"), "a file where the directory should be")
        val failure = runCatching { cache() }.exceptionOrNull()
        assertTrue(failure is CaptureValidationException, failure.toString())
    }

    @Test
    fun `a cache that stops being writable is logged and never fails the capture`() {
        val provider = CountingRpc { exactJson(transaction) }
        val rpc = CachingSolanaReadRpc(provider, cache())
        Files.writeString(directory.resolve("cache"), "replaced by a file after the cache was created")
        val result = ChainCaptureReads.transaction(rpc, signature, CaptureBudget(10, 1_000_000))
        assertEquals(5000, result.path("meta").path("fee").asInt())
        val warning = logs.list.single { it.level == Level.WARN }
        assertTrue(warning.formattedMessage.contains("signature=$signature"), warning.formattedMessage)
        assertFalse(warning.formattedMessage.contains("127.0.0.1"))
    }
}
