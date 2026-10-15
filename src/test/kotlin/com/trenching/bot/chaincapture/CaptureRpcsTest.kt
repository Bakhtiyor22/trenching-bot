package com.trenching.bot.chaincapture

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CaptureRpcsTest {
    @TempDir lateinit var directory: Path
    private val timing = ManualTiming()

    @Test
    fun `every transport built for one endpoint paces through the same limiter`() {
        LocalRpcServer { LocalRpcServer.reply(it, 200, GENESIS_REPLY) }.use { server ->
            val registry = RpcLimiterRegistry(timing)
            val settings = TestSettings(endpoint = server.endpoint)
            val wallet = CaptureRpcs.create(settings, testMapper, testClock, limiters = registry)
            val market = CaptureRpcs.create(settings.copy(readTimeout = Duration.ofSeconds(3)), testMapper, testClock, limiters = registry)
            wallet.call(ChainCaptureFormat.GET_GENESIS, emptyList(), CaptureBudget(10, 10_000))
            market.call(ChainCaptureFormat.GET_GENESIS, emptyList(), CaptureBudget(10, 10_000))
            assertEquals(listOf(settings.minRequestInterval), timing.pauses)
        }
    }

    @Test
    fun `a configured cache serves repeated transactions without another request`() {
        val requests = AtomicInteger()
        val transaction = """{"jsonrpc":"2.0","id":1,"result":{"slot":1,"meta":{"fee":5}}}"""
        LocalRpcServer { requests.incrementAndGet(); LocalRpcServer.reply(it, 200, transaction) }.use { server ->
            val cache = SharedTransactionCache(directory.resolve("cache"), testMapper)
            val settings = TestSettings(endpoint = server.endpoint)
            val signature = "4".repeat(88)
            repeat(2) {
                val rpc = CaptureRpcs.create(settings, testMapper, testClock, cache, RpcLimiterRegistry(timing))
                assertEquals(1, ChainCaptureReads.transaction(rpc, signature, CaptureBudget(10, 10_000)).path("slot").asInt())
            }
            assertEquals(1, requests.get())
        }
    }
}
