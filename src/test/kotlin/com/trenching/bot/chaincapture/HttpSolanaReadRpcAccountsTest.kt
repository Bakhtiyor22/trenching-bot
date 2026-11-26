package com.trenching.bot.chaincapture

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trenching.bot.apiusage.ApiCall
import com.trenching.bot.apiusage.ApiCallOutcome
import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.apiusage.ApiUsageRecorder
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class HttpSolanaReadRpcAccountsTest {
    private val timing = ManualTiming()
    private val exhaustedAccounts = mutableListOf<String>()
    private val calls = ConcurrentLinkedQueue<ApiCall>()
    private val recorder = ApiUsageRecorder { calls += it }
    private val usage = ApiUsageContext(ApiTask.MARKET_STEP, "capture-1")
    private val creditsReply = """{"jsonrpc":"2.0","error":{"code":429,"message":"Max Usage Reached: monthly credits"}}"""

    /** Offers the first target that was not reported exhausted, then [OutOfCredits]. */
    private fun accounts(vararg targets: RpcEndpointTarget) = object : RpcEndpointSource {
        override fun choose(): RpcEndpointChoice =
            targets.firstOrNull { it.account !in exhaustedAccounts }?.let(RpcEndpointChoice::Use) ?: RpcEndpointChoice.OutOfCredits
        override fun exhausted(account: String) { exhaustedAccounts += account }
    }

    private fun source(choice: () -> RpcEndpointChoice) = object : RpcEndpointSource {
        override fun choose() = choice()
        override fun exhausted(account: String) { exhaustedAccounts += account }
    }

    private fun rpc(settingsEndpoint: String?, endpoints: RpcEndpointSource, maxAttempts: Int = 3, registry: RpcLimiterRegistry = RpcLimiterRegistry(timing)): HttpSolanaReadRpc {
        val settings = TestSettings(endpoint = settingsEndpoint, minRequestInterval = Duration.ofMillis(500), maxConcurrentRequests = 1)
        return HttpSolanaReadRpc(settings, testMapper, testClock, timing, ProviderRequestLimiter.of(settings, timing), recorder, maxAttempts,
            endpoints, registry)
    }

    private fun <T> logged(name: String, level: Level = Level.DEBUG, block: () -> T): Pair<T, List<ILoggingEvent>> {
        val logs = ListAppender<ILoggingEvent>().also { it.start() }
        val logger = LoggerFactory.getLogger(name) as Logger
        val levelBefore = logger.level
        logger.level = level
        logger.addAppender(logs)
        try {
            return block() to logs.list.toList()
        } finally { logger.detachAppender(logs); logger.level = levelBefore }
    }

    private fun budget() = CaptureBudget(10, 100_000, usage = usage)

    private fun call(rpc: HttpSolanaReadRpc, budget: CaptureBudget = budget()) = rpc.call("getGenesisHash", emptyList(), budget)

    @Test
    fun `a rate limit 429 stays on the account, is retried and never reports the account exhausted`() {
        val requests = AtomicInteger()
        LocalRpcServer { exchange ->
            if (requests.getAndIncrement() == 0) LocalRpcServer.reply(exchange, 429, """{"error":"slow down"}""") else LocalRpcServer.reply(exchange, 200, GENESIS_REPLY)
        }.use { server ->
            val budget = budget()
            val target = RpcEndpointTarget("primary", server.endpoint)
            assertEquals("genesis", call(rpc(server.endpoint, accounts(target)), budget).asString())
            assertEquals(2, requests.get())
            assertEquals(1, budget.counters.rateLimitedResponses)
        }
        assertEquals(emptyList(), exhaustedAccounts)
        assertEquals(listOf(ApiCallOutcome.RATE_LIMITED, ApiCallOutcome.OK), calls.map { it.outcome })
    }

    @Test
    fun `a 429 that says max usage reached moves the call to the next account without using up an attempt`() {
        val first = AtomicInteger()
        val second = AtomicInteger()
        LocalRpcServer { first.incrementAndGet(); LocalRpcServer.reply(it, 429, creditsReply) }.use { a ->
            LocalRpcServer { second.incrementAndGet(); LocalRpcServer.reply(it, 200, GENESIS_REPLY) }.use { b ->
                val budget = budget()
                val endpoints = accounts(RpcEndpointTarget("primary", a.endpoint), RpcEndpointTarget("backup", b.endpoint))
                assertEquals("genesis", call(rpc(a.endpoint, endpoints, maxAttempts = 1), budget).asString())
                assertEquals(listOf(1, 1), listOf(first.get(), second.get()))
                assertEquals(2, budget.requestsUsed)
                assertEquals(listOf(0, 0), listOf(budget.counters.rateLimitedResponses, budget.counters.retries))
            }
        }
        assertEquals(listOf("primary"), exhaustedAccounts)
        assertEquals(listOf(ApiCallOutcome.FAILED, ApiCallOutcome.OK), calls.map { it.outcome })
        assertEquals(emptyList(), timing.pauses, "a credits answer holds no limiter")
    }

    @Test
    fun `with no other account the call fails CREDITS_EXHAUSTED after one request and is not retried`() {
        val requests = AtomicInteger()
        LocalRpcServer { requests.incrementAndGet(); LocalRpcServer.reply(it, 429, creditsReply.uppercase()) }.use { server ->
            val failure = assertFailsWith<CaptureProviderException> {
                call(rpc(server.endpoint, accounts(RpcEndpointTarget("primary", server.endpoint))))
            }
            assertEquals(CaptureCategory.CREDITS_EXHAUSTED, failure.category)
            assertEquals(1, requests.get())
        }
        assertEquals(listOf("primary"), exhaustedAccounts)
        assertEquals(listOf(ApiCallOutcome.FAILED), calls.map { it.outcome })
    }

    @Test
    fun `a fixed source reports nothing and fails the call on a credits answer`() {
        LocalRpcServer { LocalRpcServer.reply(it, 429, creditsReply) }.use { server ->
            val failure = assertFailsWith<CaptureProviderException> { call(rpc(server.endpoint, RpcEndpointSource.fixed(server.endpoint))) }
            assertEquals(CaptureCategory.CREDITS_EXHAUSTED, failure.category)
        }
    }

    @Test
    fun `a call moves at most once per account even when the source keeps offering them`() {
        val requests = AtomicInteger()
        LocalRpcServer { requests.incrementAndGet(); LocalRpcServer.reply(it, 429, creditsReply) }.use { a ->
            LocalRpcServer { requests.incrementAndGet(); LocalRpcServer.reply(it, 429, creditsReply) }.use { b ->
                val targets = listOf(RpcEndpointTarget("primary", a.endpoint), RpcEndpointTarget("backup", b.endpoint))
                val turn = AtomicInteger()
                val endpoints = source { RpcEndpointChoice.Use(targets[turn.getAndIncrement() % 2]) }
                val failure = assertFailsWith<CaptureProviderException> { call(rpc(a.endpoint, endpoints)) }
                assertEquals(CaptureCategory.CREDITS_EXHAUSTED, failure.category)
                assertEquals(2, requests.get())
            }
        }
    }

    @Test
    fun `a source that is out of credits sends no request`() {
        val requests = AtomicInteger()
        LocalRpcServer { requests.incrementAndGet(); LocalRpcServer.reply(it, 200, GENESIS_REPLY) }.use { server ->
            val budget = budget()
            val failure = assertFailsWith<CaptureProviderException> { call(rpc(server.endpoint, source { RpcEndpointChoice.OutOfCredits }), budget) }
            assertEquals(CaptureCategory.CREDITS_EXHAUSTED, failure.category)
            assertEquals(listOf(0, 0), listOf(requests.get(), budget.requestsUsed))
        }
        assertEquals(emptyList(), exhaustedAccounts)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a source with no endpoint fails NOT_CONFIGURED`() {
        val failure = assertFailsWith<CaptureProviderException> { call(rpc(null, RpcEndpointSource.fixed(" "))) }
        assertEquals("NOT_CONFIGURED", failure.category)
    }

    @Test
    fun `a 401 never switches accounts`() {
        LocalRpcServer { LocalRpcServer.reply(it, 401, "max usage reached") }.use { server ->
            val failure = assertFailsWith<CaptureProviderException> { call(rpc(server.endpoint, accounts(RpcEndpointTarget("primary", server.endpoint)))) }
            assertEquals("AUTHENTICATION", failure.category)
        }
        assertEquals(emptyList(), exhaustedAccounts)
    }

    @Test
    fun `each account is paced by its own limiter`() {
        LocalRpcServer { LocalRpcServer.reply(it, 200, GENESIS_REPLY) }.use { a ->
            LocalRpcServer { LocalRpcServer.reply(it, 200, GENESIS_REPLY) }.use { b ->
                val primary = RpcEndpointTarget("primary", a.endpoint)
                val backup = RpcEndpointTarget("backup", b.endpoint)
                var current = primary
                val rpc = rpc(a.endpoint, source { RpcEndpointChoice.Use(current) })
                call(rpc)
                current = backup
                call(rpc)
                assertEquals(emptyList(), timing.pauses, "the backup's first start does not wait for the primary's interval")
                current = primary
                call(rpc)
                assertEquals(listOf(Duration.ofMillis(500)), timing.pauses)
            }
        }
    }

    @Test
    fun `a result that echoes the key of the account in use is refused`() {
        val key = "secret-key-abcdef123"
        LocalRpcServer { LocalRpcServer.reply(it, 429, creditsReply) }.use { a ->
            LocalRpcServer { LocalRpcServer.reply(it, 200, """{"jsonrpc":"2.0","id":1,"result":"$key"}""") }.use { b ->
                val endpoints = accounts(RpcEndpointTarget("primary", a.endpoint), RpcEndpointTarget("backup", b.endpoint + key))
                val failure = assertFailsWith<CaptureProviderException> { call(rpc(a.endpoint, endpoints)) }
                assertEquals("UNSAFE_RESULT", failure.category)
            }
        }
    }

    @Test
    fun `an endpoint that is not valid is refused for the account it belongs to`() {
        val endpoints = source { RpcEndpointChoice.Use(RpcEndpointTarget("backup", "http://rpc.example.com/key")) }
        val failure = assertFailsWith<CaptureValidationException> { call(rpc(null, endpoints)) }
        assertEquals("ENDPOINT_TLS", failure.category)
    }

    @Test
    fun `the target prints its account and no log line carries an endpoint`() {
        val key = "secret-key-abcdef123"
        val target = RpcEndpointTarget("backup", "https://rpc.example.com/$key")
        assertEquals("RpcEndpointTarget(account=backup)", target.toString())
        assertFalse(RpcEndpointChoice.Use(target).toString().contains(key))

        val logs = ListAppender<ILoggingEvent>().also { it.start() }
        val logger = LoggerFactory.getLogger(HttpSolanaReadRpc::class.java) as Logger
        val levelBefore = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(logs)
        try {
            LocalRpcServer { LocalRpcServer.reply(it, 429, creditsReply) }.use { a ->
                LocalRpcServer { LocalRpcServer.reply(it, 429, creditsReply) }.use { b ->
                    val endpoints = accounts(RpcEndpointTarget("primary", a.endpoint), RpcEndpointTarget("backup", b.endpoint + key))
                    assertFailsWith<CaptureProviderException> { call(rpc(a.endpoint, endpoints)) }
                }
            }
        } finally { logger.detachAppender(logs); logger.level = levelBefore }

        val warning = logs.list.single { it.level == Level.WARN }.formattedMessage
        assertTrue(warning.startsWith("RPC call failed after 1 attempt: the provider account has used up its credits."), warning)
        assertTrue(warning.contains("account=backup"), warning)
        assertTrue(logs.list.none { "127.0.0.1" in it.formattedMessage || key in it.formattedMessage })
    }

    @Test
    fun `a 403 never switches accounts and is not retried`() {
        val requests = AtomicInteger()
        LocalRpcServer { requests.incrementAndGet(); LocalRpcServer.reply(it, 403, "max usage reached") }.use { a ->
            LocalRpcServer { requests.incrementAndGet(); LocalRpcServer.reply(it, 200, GENESIS_REPLY) }.use { b ->
                val endpoints = accounts(RpcEndpointTarget("primary", a.endpoint), RpcEndpointTarget("backup", b.endpoint))
                val failure = assertFailsWith<CaptureProviderException> { call(rpc(a.endpoint, endpoints)) }
                assertEquals("AUTHENTICATION", failure.category)
                assertEquals(1, requests.get())
            }
        }
        assertEquals(emptyList(), exhaustedAccounts)
    }

    @Test
    fun `a call that sends nothing because no account has credits says so at debug and never warns`() {
        val (failure, events) = logged(HttpSolanaReadRpc::class.java.name) {
            assertFailsWith<CaptureProviderException> { call(rpc("https://rpc.example.test/", source { RpcEndpointChoice.OutOfCredits })) }
        }

        assertEquals(CaptureCategory.CREDITS_EXHAUSTED, failure.category)
        assertEquals(emptyList(), events.filter { it.level.isGreaterOrEqual(Level.INFO) })
        assertTrue(events.single { it.level == Level.DEBUG }.formattedMessage.startsWith("RPC call not sent"), events.toString())
    }

    @Test
    fun `an endpoint's limiter is looked up once however many calls use it`() {
        val registry = RpcLimiterRegistry(timing)
        LocalRpcServer { LocalRpcServer.reply(it, 200, GENESIS_REPLY) }.use { a ->
            LocalRpcServer { LocalRpcServer.reply(it, 200, GENESIS_REPLY) }.use { b ->
                registry.limiterFor(b.endpoint, TestSettings(minRequestInterval = Duration.ofSeconds(5), maxConcurrentRequests = 2))
                val rpc = rpc(a.endpoint, source { RpcEndpointChoice.Use(RpcEndpointTarget("backup", b.endpoint)) }, registry = registry)

                val (_, events) = logged(RpcLimiterRegistry::class.java.name, Level.WARN) { repeat(3) { call(rpc) } }

                assertEquals(1, events.count { it.level == Level.WARN }, events.toString())
            }
        }
    }
}
