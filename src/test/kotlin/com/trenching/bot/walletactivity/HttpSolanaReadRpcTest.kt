package com.trenching.bot.walletactivity

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.CaptureBudgetException
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.CaptureTiming
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.HttpSolanaReadRpc
import com.trenching.bot.chaincapture.ProviderRequestLimiter
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import com.trenching.bot.walletactivity.application.WalletHistoryCaptureService
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import com.trenching.bot.common.WalletAddress
import tools.jackson.databind.json.JsonMapper

class HttpSolanaReadRpcTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC)
    private val wallet = "1".repeat(32)

    @Test
    fun `honors rate limits and Retry After with bounded read retries and exact body byte counts`() {
        val bodies = listOf("{}", """{"jsonrpc":"2.0","id":1,"result":"genesis"}""")
        var calls = 0
        val timing = FakeTiming()
        fakeServer { exchange ->
            val index = calls++
            exchange.requestBody.use { input ->
                val request = mapper.readTree(input)
                assertEquals("getGenesisHash", request.path("method").asString())
            }
            if (index == 0) exchange.responseHeaders.set("Retry-After", "2")
            val body = bodies[index].toByteArray()
            exchange.sendResponseHeaders(if (index == 0) 429 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }.use { server ->
            val budget = CaptureBudget(3, 1024)
            val result = rpc(server, timing).call("getGenesisHash", emptyList(), budget)
            assertEquals("genesis", result.asString())
            assertEquals(2, budget.requestsUsed)
            assertEquals(bodies.sumOf { it.toByteArray().size }.toLong(), budget.bytesUsed)
            assertTrue(timing.pauses.contains(Duration.ofSeconds(2)))
        }
    }

    @Test
    fun `a single attempt transport surfaces a 429 with its Retry After at once, without a retry or a pause`() {
        var calls = 0
        val timing = FakeTiming()
        fakeServer { exchange ->
            calls++
            exchange.responseHeaders.set("Retry-After", "2")
            exchange.sendResponseHeaders(429, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }.use { server ->
            val budget = CaptureBudget(3, 1024)
            val rpc = HttpSolanaReadRpc(WalletHistoryCaptureProperties(endpoint = "http://127.0.0.1:${server.server.address.port}/"), mapper, clock,
                timing, legacyPacing(timing), maxAttempts = 1)

            val failure = assertFailsWith<CaptureProviderException> { rpc.call("getGenesisHash", emptyList(), budget) }

            assertEquals("RATE_LIMITED", failure.category)
            assertEquals(Duration.ofSeconds(2), failure.retryAfter)
            assertEquals(listOf(1, 1, 0), listOf(calls, budget.requestsUsed, budget.counters.retries))
            assertEquals(Duration.ofSeconds(2), budget.counters.lastRetryAfter)
            assertTrue(timing.pauses.isEmpty(), timing.pauses.toString())
        }
    }

    @Test
    fun `does not retry authentication failures or retain unsafe provider response text`() {
        var calls = 0
        fakeServer { exchange ->
            calls++
            val body = "SENSITIVE_SENTINEL https://example.invalid/?api-key=fixture".toByteArray()
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }.use { server ->
            val failure = assertFailsWith<CaptureProviderException> {
                rpc(server).call("getGenesisHash", emptyList(), CaptureBudget(3, 1024))
            }
            assertEquals("AUTHENTICATION", failure.category)
            assertEquals(1, calls)
            assertFalse(failure.message.orEmpty().contains("SENSITIVE_SENTINEL"))
            assertEquals(null, failure.cause)
        }
    }

    @Test
    fun `caps transient attempts at three and counts retry response bytes`() {
        fakeServer { exchange ->
            exchange.sendResponseHeaders(503, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }.use { server ->
            val budget = CaptureBudget(10, 1024)
            assertFailsWith<CaptureProviderException> { rpc(server).call("getGenesisHash", emptyList(), budget) }
            assertEquals(3, budget.requestsUsed)
            assertEquals(6, budget.bytesUsed)
        }
    }

    @Test
    fun `fails closed when response budget is exceeded and never retries budget failures`() {
        fakeServer { exchange ->
            val body = ByteArray(1024) { 32 }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }.use { server ->
            val budget = CaptureBudget(10, 20)
            assertFailsWith<CaptureBudgetException> { rpc(server).call("getGenesisHash", emptyList(), budget) }
            assertEquals(1, budget.requestsUsed)
        }
    }

    @Test
    fun `a Retry After beyond the remaining capture deadline ends the call with a rate limit that carries the wait, without another request`() {
        fakeServer { exchange ->
            exchange.responseHeaders.set("Retry-After", "1200")
            exchange.sendResponseHeaders(429, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }.use { server ->
            val budget = CaptureBudget(10, 1024)
            val failure = assertFailsWith<CaptureProviderException> { rpc(server).call("getGenesisHash", emptyList(), budget) }
            assertEquals(listOf("RATE_LIMITED", Duration.ofMinutes(20), 1), listOf(failure.category, failure.retryAfter, failure.requestsUsed))
            assertEquals(1, budget.requestsUsed)
        }
    }

    @Test
    fun `a Retry After of either form is capped at thirty minutes, far future dates included, and a past date is no wait at all`() {
        val cases = mapOf(
            "99999999999" to Duration.ofMinutes(30),
            "Fri, 31 Dec 9999 23:59:59 GMT" to Duration.ofMinutes(30),
            "Sat, 03 Oct 2026 00:20:00 GMT" to Duration.ofMinutes(20),
            "Mon, 01 Jan 2001 00:00:00 GMT" to Duration.ZERO,
        )
        cases.forEach { (header, expected) ->
            fakeServer { exchange ->
                exchange.responseHeaders.set("Retry-After", header)
                exchange.sendResponseHeaders(429, 2)
                exchange.responseBody.use { it.write("{}".toByteArray()) }
            }.use { server ->
                val failure = assertFailsWith<CaptureProviderException>(header) { rpc(server).call("getGenesisHash", emptyList(), CaptureBudget(10, 1024)) }
                assertEquals("RATE_LIMITED", failure.category, header)
                assertEquals(expected, failure.retryAfter, header)
            }
        }
    }

    @Test
    fun `permits only the explicit read method allowlist without contacting a server`() {
        val timing = FakeTiming()
        val client = HttpSolanaReadRpc(WalletHistoryCaptureProperties(), mapper, clock, timing, legacyPacing(timing))
        assertFailsWith<CaptureValidationException> { client.call("forbidden", emptyList(), CaptureBudget(1, 1024)) }
        val failure = assertFailsWith<CaptureProviderException> { client.call("getGenesisHash", emptyList(), CaptureBudget(1, 1024)) }
        assertEquals("NOT_CONFIGURED", failure.category)
    }

    @Test
    fun `rejects a result echoing the configured endpoint instead of preserving provider credentials in raw artifacts`() {
        lateinit var endpoint: String
        fakeServer { exchange ->
            val body = """{"jsonrpc":"2.0","id":1,"result":"$endpoint"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }.use { server ->
            endpoint = "http://127.0.0.1:${server.server.address.port}/?api-key=FIXTURE_KEY_VALUE"
            val failure = assertFailsWith<CaptureProviderException> {
                rpc(endpoint).call("getGenesisHash", emptyList(), CaptureBudget(1, 1024))
            }
            assertEquals("UNSAFE_RESULT", failure.category)
            assertFalse(failure.message.orEmpty().contains("FIXTURE_KEY_VALUE"))
            assertFalse(failure.message.orEmpty().contains("127.0.0.1"))
        }
    }

    @Test
    fun `fails closed when a valid transaction echoes any configured credential fragment`() {
        val cases = listOf(
            Echo("query value alone in logs", "?api-key=SENSITIVE_SENTINEL", "\"SENSITIVE_SENTINEL\"", "SENSITIVE_SENTINEL"),
            Echo("query value in another case", "?api-key=SENSITIVE_SENTINEL", "\"Program log: sensitive_sentinel\"", "SENSITIVE_SENTINEL"),
            Echo("encoded key echoed decoded", "?api-key=FAKE%2FKEY%3D%2B12345", "\"FAKE/KEY=+12345\"", "FAKE"),
            Echo("encoded key echoed encoded", "?api-key=FAKE%2FKEY%3D%2B12345", "\"FAKE%2FKEY%3D%2B12345\"", "FAKE"),
            Echo("json unicode escaped echo", "?api-key=SENSITIVE_SENTINEL", "\"\\u0053ENSITIVE_SENTINEL\"", "SENSITIVE_SENTINEL"),
            Echo("path segment token", "FAKEPATHTOKEN123/", "\"FAKEPATHTOKEN123\"", "FAKEPATHTOKEN123"),
            Echo("encoded path token echoed decoded", "FAKE%20PATH%20TOKEN/", "\"FAKE PATH TOKEN\"", "FAKE"),
        )
        cases.forEach { case ->
            fakeServer { exchange ->
                val body = """{"jsonrpc":"2.0","id":1,"result":${transactionJson(logs = "[${case.log}]")}}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }.use { server ->
                val failure = assertFailsWith<CaptureProviderException>(case.name) {
                    rpc("http://127.0.0.1:${server.server.address.port}/${case.endpointSuffix}")
                        .call("getTransaction", emptyList(), CaptureBudget(1, 4096))
                }
                assertEquals("UNSAFE_RESULT", failure.category, case.name)
                assertFalse(failure.message.orEmpty().contains(case.secret), case.name)
            }
        }
    }

    @Test
    fun `fails closed when a configured credential fragment is echoed as an object key`() {
        fakeServer { exchange ->
            val body = """{"jsonrpc":"2.0","id":1,"result":{"meta":{"SENSITIVE_SENTINEL":1}}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }.use { server ->
            val failure = assertFailsWith<CaptureProviderException> {
                rpc("http://127.0.0.1:${server.server.address.port}/?api-key=SENSITIVE_SENTINEL")
                    .call("getTransaction", emptyList(), CaptureBudget(1, 1024))
            }
            assertEquals("UNSAFE_RESULT", failure.category)
        }
    }

    @Test
    fun `ignores short endpoint fragments so public data matching them is still accepted`() {
        fakeServer { exchange ->
            val body = """{"jsonrpc":"2.0","id":1,"result":"v1 key-1"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }.use { server ->
            assertEquals("v1 key-1", rpc("http://127.0.0.1:${server.server.address.port}/v1/?key=key-1")
                .call("getGenesisHash", emptyList(), CaptureBudget(1, 1024)).asString())
        }
    }

    @Test
    fun `never stores a credential echo from a transaction that is otherwise valid`() {
        val signature = "1".repeat(64)
        fakeServer { exchange ->
            val request = mapper.readTree(exchange.requestBody)
            val body = when (request.path("method").asString()) {
                "getGenesisHash" -> """{"jsonrpc":"2.0","id":1,"result":"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"}"""
                "getSignaturesForAddress" -> """{"jsonrpc":"2.0","id":1,"result":[{"signature":"$signature","slot":100,"err":null}]}"""
                "getTransaction" -> """{"jsonrpc":"2.0","id":1,"result":${transactionJson(logs = "[\"SENSITIVE_SENTINEL\"]")}}"""
                else -> error("Unexpected method")
            }.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }.use { server ->
            val result = WalletHistoryCaptureService(rpc("http://127.0.0.1:${server.server.address.port}/?api-key=SENSITIVE_SENTINEL"), mapper, clock)
                .capture(WalletHistoryCaptureRequest(WalletAddress.of(wallet), directory, limit = 1))
            assertEquals(WalletHistoryOutcome.PROVIDER_UNAVAILABLE, result.manifest.records.single().outcome)
            assertFalse(result.manifest.complete)
            assertFalse(Files.exists(directory.resolve("transactions/$signature.json")))
            assertFalse(Files.readString(directory.resolve("manifest.json")).contains("SENSITIVE_SENTINEL"))
        }
    }

    @Test
    fun `completes discovery over public memo links without storing memo text`() {
        val signature = "1".repeat(64)
        fakeServer { exchange ->
            val request = mapper.readTree(exchange.requestBody)
            val body = when (request.path("method").asString()) {
                "getGenesisHash" -> """{"jsonrpc":"2.0","id":1,"result":"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"}"""
                "getSignaturesForAddress" -> """{"jsonrpc":"2.0","id":1,"result":[{"signature":"$signature","slot":100,"err":null,"memo":"visit https://spam.invalid","blockTime":1790985600,"confirmationStatus":"finalized"}]}"""
                "getTransaction" -> """{"jsonrpc":"2.0","id":1,"result":${transactionJson()}}"""
                else -> error("Unexpected method")
            }.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }.use { server ->
            val result = WalletHistoryCaptureService(rpc(server), mapper, clock)
                .capture(WalletHistoryCaptureRequest(WalletAddress.of(wallet), directory, limit = 1))
            assertTrue(result.manifest.discoveryComplete)
            assertTrue(result.manifest.complete)
            assertFalse(Files.readString(directory.resolve("manifest.json")).contains("spam.invalid"))
        }
    }

    @Test
    fun `withholds a transaction carrying public link text and continues to the next selected signature`() {
        val withheld = "1".repeat(64)
        val next = "1".repeat(63) + "2"
        fakeServer { exchange ->
            val request = mapper.readTree(exchange.requestBody)
            val body = when (request.path("method").asString()) {
                "getGenesisHash" -> """{"jsonrpc":"2.0","id":1,"result":"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"}"""
                "getSignaturesForAddress" -> """{"jsonrpc":"2.0","id":1,"result":[{"signature":"$withheld","slot":100,"err":null},{"signature":"$next","slot":99,"err":null}]}"""
                "getTransaction" -> if (request.path("params")[0].asString() == withheld) {
                    """{"jsonrpc":"2.0","id":1,"result":${transactionJson(logs = "[\"Program log: Memo (len 26): visit https://spam.invalid\"]")}}"""
                } else """{"jsonrpc":"2.0","id":1,"result":${transactionJson(signature = next, slot = 99)}}"""
                else -> error("Unexpected method")
            }.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }.use { server ->
            val result = WalletHistoryCaptureService(rpc(server), mapper, clock)
                .capture(WalletHistoryCaptureRequest(WalletAddress.of(wallet), directory, limit = 2))
            assertEquals(listOf(WalletHistoryOutcome.WITHHELD_UNSAFE_TEXT, WalletHistoryOutcome.UNCLASSIFIED), result.manifest.records.map { it.outcome })
            assertEquals(null, result.manifest.records.first().artifact)
            assertFalse(Files.exists(directory.resolve("transactions/$withheld.json")))
            assertTrue(result.manifest.complete)
            assertEquals(4, result.manifest.requestsUsed)
            assertFalse(Files.readString(directory.resolve("manifest.json")).contains("spam.invalid"))
            assertEquals(result.manifest, WalletHistoryArtifactStore(mapper).inspect(directory).manifest)
        }
    }

    @Test
    fun `reports hostile JSON-RPC envelopes as typed provider failures without throwing anything else`() {
        val envelopes = mapOf(
            """{"jsonrpc":"2.0","id":99999999999999999999999,"result":"x"}""" to "MALFORMED",
            """{"jsonrpc":"2.0","id":1.5,"result":"x"}""" to "MALFORMED",
            """{"jsonrpc":{},"id":1,"result":"x"}""" to "MALFORMED",
            """{"jsonrpc":"2.0","id":1}""" to "MALFORMED",
            """[]""" to "MALFORMED",
            "\"x\"" to "MALFORMED",
            """{"jsonrpc":"2.0","id":1,"error":{"code":"x"}}""" to "RPC_ERROR",
            """{"jsonrpc":"2.0","id":1,"error":{"code":1.5}}""" to "RPC_ERROR",
            """{"jsonrpc":"2.0","id":1,"error":{"code":99999999999999999999999}}""" to "RPC_ERROR",
            """{"jsonrpc":"2.0","id":1,"error":{}}""" to "RPC_ERROR",
            """{"jsonrpc":"2.0","id":1,"error":"x"}""" to "RPC_ERROR",
            """{"jsonrpc":"2.0","id":1,"error":[]}""" to "RPC_ERROR",
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32007,"message":"Slot 1 was skipped"}}""" to "SLOT_SKIPPED",
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32009,"message":"Slot 1 was skipped"}}""" to "SLOT_SKIPPED",
        )
        envelopes.forEach { (body, category) ->
            fakeServer { exchange ->
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }.use { server ->
                val failure = assertFailsWith<CaptureProviderException>(body) {
                    rpc(server).call("getTransaction", emptyList(), CaptureBudget(1, 1024))
                }
                assertEquals(category, failure.category, body)
            }
        }
    }

    @Test
    fun `uses exact decimal JSON nodes for raw token amounts without binary rounding`() {
        fakeServer { exchange ->
            val body = """{"jsonrpc":"2.0","id":1,"result":{"uiAmount":0.123456789123456789123456789}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }.use { server ->
            val result = rpc(server).call("getTransaction", emptyList(), CaptureBudget(1, 1024))
            assertEquals(java.math.BigDecimal("0.123456789123456789123456789"), result.path("uiAmount").decimalValue())
        }
    }

    @Test
    fun `records an unsupported transaction version from actual RPC error without storing provider text`() {
        val signature = "1".repeat(64)
        fakeServer { exchange ->
            val request = mapper.readTree(exchange.requestBody)
            val body = when (request.path("method").asString()) {
                "getGenesisHash" -> """{"jsonrpc":"2.0","id":1,"result":"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"}"""
                "getSignaturesForAddress" -> """{"jsonrpc":"2.0","id":1,"result":[{"signature":"$signature","slot":100,"err":null}]}"""
                "getTransaction" -> """{"jsonrpc":"2.0","id":1,"error":{"code":-32015,"message":"SENSITIVE_SENTINEL"}}"""
                else -> error("Unexpected method")
            }.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }.use { server ->
            val result = WalletHistoryCaptureService(rpc(server), mapper, clock)
                .capture(WalletHistoryCaptureRequest(WalletAddress.of("1".repeat(32)), directory, limit = 1))
            assertEquals(WalletHistoryOutcome.UNSUPPORTED_VERSION, result.manifest.records.single().outcome)
            assertEquals(null, result.manifest.records.single().artifact)
            assertEquals(3, result.manifest.requestsUsed)
            assertTrue(result.manifest.complete)
        }
    }

    @Test
    fun `applies the response timeout and bounded retries to a stalled local server`() {
        val release = CountDownLatch(1)
        fakeServer { _ ->
            try { release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }.use { server ->
            val budget = CaptureBudget(10, 1024)
            try {
                val timing = FakeTiming()
                val client = HttpSolanaReadRpc(WalletHistoryCaptureProperties(
                    endpoint = "http://127.0.0.1:${server.server.address.port}/", readTimeout = Duration.ofMillis(50),
                ), mapper, clock, timing, legacyPacing(timing))
                val logs = ListAppender<ILoggingEvent>().also { it.start() }
                val logger = LoggerFactory.getLogger(HttpSolanaReadRpc::class.java) as Logger
                logger.addAppender(logs)
                try {
                    val failure = assertFailsWith<CaptureProviderException> { client.call("getGenesisHash", emptyList(), budget) }
                    assertEquals("IO", failure.category)
                } finally { logger.detachAppender(logs) }
                assertEquals(3, budget.requestsUsed)
                val messages = logs.list.map { it.formattedMessage }
                assertEquals(3, messages.count { Regex("cause=\\w*Timeout\\w*Exception\\b").containsMatchIn(it) }, messages.toString())
                assertTrue(messages.any { it.startsWith("chain_capture rpc_failed operation=getGenesisHash category=IO") }, messages.toString())
                assertTrue(messages.none { "127.0.0.1" in it }, "the endpoint never reaches the log")
            } finally { release.countDown() }
        }
    }

    private fun rpc(server: Server, timing: FakeTiming = FakeTiming()) = rpc("http://127.0.0.1:${server.server.address.port}/", timing)

    private fun rpc(endpoint: String, timing: FakeTiming = FakeTiming()) =
        HttpSolanaReadRpc(WalletHistoryCaptureProperties(endpoint = endpoint), mapper, clock, timing, legacyPacing(timing))

    /** One request at a time with 500 ms between starts: the pacing these tests were written against. */
    private fun legacyPacing(timing: CaptureTiming) = ProviderRequestLimiter(1, Duration.ofMillis(500), timing)

    private fun transactionJson(signature: String = "1".repeat(64), slot: Long = 100, logs: String = "[]") =
        """{"slot":$slot,"blockTime":1790985600,"version":0,"meta":{"err":null,"fee":5000,"preBalances":[1000000],"postBalances":[995000],"logMessages":$logs},""" +
            """"transaction":{"signatures":["$signature"],"message":{"accountKeys":[{"pubkey":"$wallet","signer":true,"writable":true}],""" +
            """"instructions":[{"programId":"$wallet","accounts":[],"data":""}],"recentBlockhash":"$wallet"}}}"""

    private data class Echo(val name: String, val endpointSuffix: String, val log: String, val secret: String)

    private fun fakeServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): Server {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/") { exchange -> try { handler(exchange) } finally { exchange.close() } }
        server.start()
        return Server(server, executor)
    }

    private class Server(val server: HttpServer, private val executor: java.util.concurrent.ExecutorService) : AutoCloseable {
        override fun close() { server.stop(0); executor.shutdownNow() }
    }

    private class FakeTiming : CaptureTiming {
        var nanos: Long = 0
        val pauses = mutableListOf<Duration>()
        override fun nanoTime(): Long = nanos
        override fun pause(duration: Duration) { pauses += duration; nanos += duration.toNanos() }
        override fun jitterMillis(attempt: Int): Long = 0
    }
}
