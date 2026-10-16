package com.trenching.bot.chaincapture

import com.trenching.bot.apiusage.ApiCall
import com.trenching.bot.apiusage.ApiCallOutcome
import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.apiusage.ApiUsageRecorder
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HttpSolanaReadRpcUsageTest {
    @TempDir lateinit var directory: Path
    private val calls = ConcurrentLinkedQueue<ApiCall>()
    private val recorder = ApiUsageRecorder { calls += it }
    private val usage = ApiUsageContext(ApiTask.MARKET_STEP, "capture-1")

    @Test
    fun `a successful reply is one OK call with its body bytes under the budget's context`() {
        LocalRpcServer { LocalRpcServer.reply(it, 200, GENESIS_REPLY) }.use { server ->
            rpc(server.endpoint).call(ChainCaptureFormat.GET_GENESIS, emptyList(), budget())
        }

        assertEquals(listOf(ApiCall(ApiProvider.SOLANA_RPC, "getGenesisHash", usage, ApiCallOutcome.OK, GENESIS_REPLY.length.toLong())), calls.toList())
    }

    @Test
    fun `a 429 and its retry are two calls, the first rate limited`() {
        val replies = AtomicInteger()
        val limited = """{"error":"slow down"}"""
        LocalRpcServer {
            if (replies.getAndIncrement() == 0) LocalRpcServer.reply(it, 429, limited, mapOf("Retry-After" to "1"))
            else LocalRpcServer.reply(it, 200, GENESIS_REPLY)
        }.use { server ->
            rpc(server.endpoint).call(ChainCaptureFormat.GET_GENESIS, emptyList(), budget())
        }

        assertEquals(listOf(ApiCallOutcome.RATE_LIMITED to limited.length.toLong(), ApiCallOutcome.OK to GENESIS_REPLY.length.toLong()),
            calls.map { it.outcome to it.responseBytes })
    }

    @Test
    fun `every attempt of a failing 500 is a failed call`() {
        LocalRpcServer { LocalRpcServer.reply(it, 500, "oops") }.use { server ->
            assertFailsWith<CaptureProviderException> { rpc(server.endpoint).call(ChainCaptureFormat.GET_GENESIS, emptyList(), budget()) }
        }

        assertEquals(List(3) { ApiCallOutcome.FAILED to 4L }, calls.map { it.outcome to it.responseBytes })
    }

    @Test
    fun `a json-rpc error under http 200 is a failed call`() {
        val error = """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"bad params"}}"""
        LocalRpcServer { LocalRpcServer.reply(it, 200, error) }.use { server ->
            assertFailsWith<CaptureProviderException> { rpc(server.endpoint).call(ChainCaptureFormat.GET_GENESIS, emptyList(), budget()) }
        }

        assertEquals(listOf(ApiCallOutcome.FAILED to error.length.toLong()), calls.map { it.outcome to it.responseBytes })
    }

    @Test
    fun `each timed out attempt is a failed call with no bytes`() {
        val release = CountDownLatch(1)
        LocalRpcServer { exchange ->
            exchange.requestBody.readAllBytes()
            release.await(5, TimeUnit.SECONDS)
        }.use { server ->
            try {
                assertFailsWith<CaptureProviderException> {
                    rpc(server.endpoint, readTimeout = Duration.ofMillis(50)).call(ChainCaptureFormat.GET_GENESIS, emptyList(), budget())
                }
            } finally {
                release.countDown()
            }
        }

        assertEquals(List(3) { ApiCallOutcome.FAILED to 0L }, calls.map { it.outcome to it.responseBytes })
    }

    @Test
    fun `a request refused by the budget is never sent and never counted`() {
        LocalRpcServer { LocalRpcServer.reply(it, 200, GENESIS_REPLY) }.use { server ->
            assertFailsWith<CaptureBudgetException> {
                rpc(server.endpoint).call(ChainCaptureFormat.GET_GENESIS, emptyList(), CaptureBudget(0, 10_000, usage = usage))
            }
        }

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a cached transaction is a cache hit with no request`() {
        val requests = AtomicInteger()
        val signature = "4".repeat(88)
        val reply = """{"jsonrpc":"2.0","id":1,"result":${validTransactionJson(signature, slot = 1)}}"""
        LocalRpcServer { requests.incrementAndGet(); LocalRpcServer.reply(it, 200, reply) }.use { server ->
            val rpc = CaptureRpcs.create(TestSettings(endpoint = server.endpoint), testMapper, testClock,
                SharedTransactionCache(directory.resolve("cache"), testMapper), RpcLimiterRegistry(ManualTiming()), recorder)
            repeat(2) { ChainCaptureReads.transaction(rpc, signature, budget()) }
        }

        assertEquals(1, requests.get())
        assertEquals(listOf(ApiCallOutcome.OK to reply.length.toLong(), ApiCallOutcome.CACHE_HIT to 0L), calls.map { it.outcome to it.responseBytes })
        assertEquals(List(2) { "getTransaction" }, calls.map { it.operation })
        assertTrue(calls.all { it.context == usage })
    }

    private fun budget() = CaptureBudget(10, 100_000, usage = usage)

    private fun rpc(endpoint: String, readTimeout: Duration = SolanaRpcSettings.DEFAULT_READ_TIMEOUT): HttpSolanaReadRpc {
        val timing = ManualTiming()
        val settings = TestSettings(endpoint = endpoint, readTimeout = readTimeout)
        return HttpSolanaReadRpc(settings, testMapper, testClock, timing, ProviderRequestLimiter.of(settings, timing), recorder)
    }
}
