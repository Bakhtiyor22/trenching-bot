package com.trenching.bot.chaincapture

import java.time.Duration
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.junit.jupiter.api.Test

class RpcLimiterRegistryTest {
    @Test
    fun `callers using the same endpoint share one limiter whatever settings object they hold`() {
        val registry = RpcLimiterRegistry(ManualTiming())
        val wallet = registry.limiterFor(TestSettings(endpoint = "http://127.0.0.1:1/?api-key=A"))
        val market = registry.limiterFor(TestSettings(endpoint = "http://127.0.0.1:1/?api-key=A", readTimeout = Duration.ofSeconds(3)))
        assertSame(wallet, market)
    }

    @Test
    fun `surrounding whitespace and a trailing slash do not make a different endpoint`() {
        val registry = RpcLimiterRegistry(ManualTiming())
        val plain = registry.limiterFor(TestSettings(endpoint = "https://rpc.example.invalid/v1?api-key=A"))
        assertSame(plain, registry.limiterFor(TestSettings(endpoint = "  https://rpc.example.invalid/v1?api-key=A \n")))
        val root = registry.limiterFor(TestSettings(endpoint = "http://127.0.0.1:1"))
        assertSame(root, registry.limiterFor(TestSettings(endpoint = "http://127.0.0.1:1/")))
        assertNotSame(plain, root)
    }

    @Test
    fun `different endpoints get independent limiters`() {
        val registry = RpcLimiterRegistry(ManualTiming())
        assertNotSame(registry.limiterFor(TestSettings(endpoint = "http://127.0.0.1:1/")), registry.limiterFor(TestSettings(endpoint = "http://127.0.0.1:2/")))
    }

    @Test
    fun `the first registration for an endpoint wins when later settings differ`() {
        val registry = RpcLimiterRegistry(ManualTiming())
        val first = registry.limiterFor(TestSettings(endpoint = "http://127.0.0.1:1/", maxConcurrentRequests = 2))
        assertSame(first, registry.limiterFor(TestSettings(endpoint = "http://127.0.0.1:1/", maxConcurrentRequests = 8)))
    }

    @Test
    fun `separate registries are isolated from each other and from the process registry`() {
        val settings = TestSettings(endpoint = "http://127.0.0.1:1/")
        assertNotSame(RpcLimiterRegistry(ManualTiming()).limiterFor(settings), RpcLimiterRegistry(ManualTiming()).limiterFor(settings))
        assertNotSame(RpcLimiterRegistry(ManualTiming()).limiterFor(settings), RpcLimiterRegistry.shared.limiterFor(settings))
    }
}
