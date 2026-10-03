package com.trenching.bot.console.solana

import com.sun.net.httpserver.HttpServer
import com.trenching.bot.console.config.SolanaRpcProperties
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.ResponseActions
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

object SolanaRpcTestData {
    const val SECRET = "SECRET-KEY-123"
    const val RPC_URL = "https://rpc.example.test/?api-key=$SECRET"
    const val RPC_HOST = "rpc.example.test"
    const val DEVNET_GENESIS = "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG"
    const val TRACKED_WALLET = "So11111111111111111111111111111111111111112"
    const val TOKEN_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    fun result(json: String): String = """{"jsonrpc":"2.0","id":1,"result":$json}"""

    fun error(code: Long, message: String): String =
        """{"jsonrpc":"2.0","id":1,"error":{"code":$code,"message":"$message"}}"""

    fun MockRestServiceServer.expectCall(rpcMethod: String, url: String = RPC_URL): ResponseActions =
        expect(ExpectedCount.once(), requestTo(url))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.method").value(rpcMethod))

    fun MockRestServiceServer.respond(rpcMethod: String, body: String) {
        expectCall(rpcMethod).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    fun properties(rpcUrl: String?, readTimeout: Duration = Duration.ofSeconds(10)) =
        SolanaRpcProperties(rpcUrl = rpcUrl, readTimeout = readTimeout)
}

class MutableClock(private var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

/** A local JSON-RPC endpoint on 127.0.0.1, so the real JDK HTTP client path is exercised without a network. */
class FakeSolanaRpcServer private constructor(private val server: HttpServer) : AutoCloseable {
    private val requests = AtomicInteger()
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val release = CountDownLatch(1)
    @Volatile private var status = 200
    @Volatile private var body = ""
    @Volatile private var hang = false

    val requestCount: Int get() = requests.get()

    fun url(query: String = "api-key=${SolanaRpcTestData.SECRET}"): String =
        "http://127.0.0.1:${server.address.port}/?$query"

    fun respondWith(status: Int, body: String = "") {
        this.status = status
        this.body = body
    }

    fun hangUntilClosed() {
        hang = true
    }

    override fun close() {
        release.countDown()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun start(): FakeSolanaRpcServer {
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.readAllBytes()
            if (hang) release.await(1, TimeUnit.MINUTES)
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.executor = executor
        server.start()
        return this
    }

    companion object {
        fun start(): FakeSolanaRpcServer =
            FakeSolanaRpcServer(HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)).start()
    }
}
