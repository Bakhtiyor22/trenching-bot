package com.trenching.bot.chaincapture

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

internal val testMapper: JsonMapper = JsonMapper.builder().build()
internal val testClock: Clock = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC)
internal const val GENESIS_REPLY = """{"jsonrpc":"2.0","id":1,"result":"genesis"}"""

/**
 * A `jsonParsed` transaction that [CapturedTransactionCheck] accepts for [signature], carrying decimals that must survive
 * a cache round trip exactly.
 */
internal fun validTransactionJson(signature: String, slot: Long = 100, fee: Int = 5000, version: String = "0"): String {
    val address = "1".repeat(32)
    return """{"slot":$slot,"blockTime":1790985600,"version":$version,"meta":{"err":null,"fee":$fee,"preBalances":[1],"postBalances":[1],""" +
        """"preTokenBalances":[{"accountIndex":0,"mint":"$address","uiTokenAmount":{"amount":"1","decimals":6,"uiAmount":0.123456789123456789123456789,"scaled":1.50,"big":1E+3}}]},""" +
        """"transaction":{"signatures":["$signature"],"message":{"accountKeys":[{"pubkey":"$address","signer":true,"writable":true}],"instructions":[],"recentBlockhash":"$address"}}}"""
}

/** A virtual clock: pausing advances it by exactly the requested duration, so waits are deterministic. */
internal class ManualTiming : CaptureTiming {
    var nanos: Long = 0
    val pauses = mutableListOf<Duration>()
    override fun nanoTime(): Long = nanos
    override fun pause(duration: Duration) { pauses += duration; nanos += duration.toNanos() }
    override fun jitterMillis(attempt: Int): Long = 0
}

/** Parses JSON the way the RPC transport does, so decimals stay exact. */
internal fun exactJson(json: String): JsonNode =
    testMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(json)

/** Runs [task] on [threads] threads released together and rethrows the first failure. */
internal fun runConcurrently(threads: Int, task: (Int) -> Unit) {
    val executor = Executors.newFixedThreadPool(threads)
    try {
        val gate = CountDownLatch(1)
        val futures: List<Future<*>> = (0 until threads).map { index -> executor.submit { gate.await(); task(index) } }
        gate.countDown()
        futures.forEach { it.get() }
    } finally { executor.shutdownNow() }
}

/** A local JSON-RPC endpoint on 127.0.0.1 whose [handler] sees every request. */
internal class LocalRpcServer(handler: (HttpExchange) -> Unit) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try { handler(exchange) } finally { exchange.close() }
        }
        server.start()
    }

    val endpoint: String get() = "http://127.0.0.1:${server.address.port}/"

    override fun close() { server.stop(0); executor.shutdownNow() }

    companion object {
        fun reply(exchange: HttpExchange, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
            exchange.requestBody.readAllBytes()
            headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}

internal data class TestSettings(
    override val endpoint: String? = null,
    override val connectTimeout: Duration = SolanaRpcSettings.DEFAULT_CONNECT_TIMEOUT,
    override val readTimeout: Duration = SolanaRpcSettings.DEFAULT_READ_TIMEOUT,
    override val operationTimeout: Duration = SolanaRpcSettings.DEFAULT_OPERATION_TIMEOUT,
    override val maxConcurrentRequests: Int = SolanaRpcSettings.DEFAULT_MAX_CONCURRENT_REQUESTS,
    override val minRequestInterval: Duration = SolanaRpcSettings.DEFAULT_MIN_REQUEST_INTERVAL,
) : SolanaRpcSettings
