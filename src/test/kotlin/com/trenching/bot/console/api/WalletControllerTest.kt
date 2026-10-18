package com.trenching.bot.console.api

import com.trenching.bot.apiusage.ApiCall
import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.LocalRpcServer
import com.trenching.bot.chaincapture.RpcLimiterRegistry
import com.trenching.bot.chaincapture.TestSettings
import com.trenching.bot.console.application.WalletActivityCache
import com.trenching.bot.console.application.WalletActivityConsoleService
import com.trenching.bot.console.capture.CaptureFileStore
import com.trenching.bot.console.capture.CapturePathPolicy
import com.trenching.bot.console.capture.CaptureSessionRecord
import com.trenching.bot.console.capture.CaptureTokenIndex
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.console.api.dto.CaptureWalletSettings
import com.trenching.bot.console.domain.CapturedTokens
import com.trenching.bot.console.mapper.ConsoleErrorMapperImpl
import com.trenching.bot.console.mapper.WalletActivityResponseMapperImpl
import com.trenching.bot.walletactivity.FakeActivityRpc
import com.trenching.bot.walletactivity.WalletActivityFixtures
import com.trenching.bot.walletactivity.WalletActivityFixtures.BUY_SIGNATURE
import com.trenching.bot.walletactivity.WalletActivityFixtures.DECU
import com.trenching.bot.walletactivity.WalletActivityFixtures.SELL_SIGNATURE
import com.trenching.bot.walletactivity.WalletActivityFixtures.SELL_SLOT
import com.trenching.bot.walletactivity.WalletActivityFixtures.V3_MINT
import com.trenching.bot.walletactivity.WalletActivityFixtures.V3_SIGNATURE
import com.trenching.bot.walletactivity.WalletActivityLookup
import com.trenching.bot.walletactivity.WalletActivityLookups
import com.trenching.bot.walletactivity.WalletActivityResult
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import com.trenching.bot.walletactivity.application.RecentWalletActivityService
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class WalletControllerTest {
    @TempDir lateinit var root: Path
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()
    private val clock = MutableClock(Instant.parse("2026-10-17T10:00:00Z"))
    private val recorded = ConcurrentLinkedQueue<ApiCall>()
    private val recorder = ApiUsageRecorder { recorded += it }
    private val thirdBuySignature = WalletActivityFixtures.THIRD_BUY_SIGNATURE

    @Test
    fun `Decu's accumulator page lists four actions newest first, with its cost, and names the capture already holding a mint`() {
        CaptureFileStore(CapturePathPolicy(root), json).also { it.initialize() }.use { store ->
            store.save(CaptureSessionRecord(id = "decu-v3", requestId = "decu-v3", fingerprint = "f", name = "Decu v3",
                createdAt = Instant.parse("2026-10-16T20:13:00Z"), wallet = CaptureWalletSettings(DECU.value, "wallet-token", mint = V3_MINT),
                walletAttemptId = "w", marketAttemptId = null, ratesAttemptId = null), create = true)
            LocalRpcServer(provider(decuProvider())).use { server ->
                val lookup = WalletActivityLookups.create(TestSettings(endpoint = server.endpoint, minRequestInterval = Duration.ofMillis(20)),
                    json, clock, transactionCache = null, recorder, RpcLimiterRegistry())

                val body = body(mvc(lookup, CaptureTokenIndex(store)), "/api/wallets/${DECU.value}/activity?limit=20", 200)

                assertEquals(listOf("wallet", "source", "actions", "unreadable", "scanned", "nextBefore", "requestsUsed", "cachedAt"), body.propertyNames().toList())
                assertEquals(DECU.value, body["wallet"].asString())
                assertEquals("pumpfun-user-volume-accumulator", body["source"].asString())
                assertEquals(listOf(V3_SIGNATURE, SELL_SIGNATURE, thirdBuySignature, BUY_SIGNATURE), body["actions"].toList().map { it["signature"].asString() })
                assertEquals(listOf("BUY", "SELL", "BUY", "BUY"), body["actions"].toList().map { it["side"].asString() })
                assertEquals(1, body["unreadable"].asInt())
                assertEquals(6, body["scanned"].asInt())
                assertTrue(body["nextBefore"].isNull)
                assertTrue(body["requestsUsed"].asInt() <= 7, "requestsUsed=${body["requestsUsed"]}")
                assertTrue(body["cachedAt"].isNull)
                assertEquals("""[{"captureId":"decu-v3","displayName":"Decu v3"}]""", body["actions"][0]["capturedIn"].toString())
                assertEquals(listOf("[]", "[]", "[]"), body["actions"].drop(1).map { it["capturedIn"].toString() })
                assertEquals(listOf(false, false, true, false), body["actions"].toList().map { it["earlierActionInPage"].asBoolean() })
                assertEquals(listOf(false, true, false, false), body["actions"].toList().map { it["overSignalThreshold"].asBoolean() })
            }
        }

        val calls = recorded.toList()
        assertTrue(calls.all { it.context.task == ApiTask.WALLET_ACTIVITY && it.context.captureId == null })
        assertEquals(1, calls.count { it.operation == "getSignaturesForAddress" })
        assertTrue(calls.count { it.operation == "getTransaction" } <= 5)
    }

    @Test
    fun `an action carries every contract field, amounts as strings`() {
        val body = body(mvc(decuLookup()), "/api/wallets/${DECU.value}/activity", 200)

        val sell = body["actions"][1]
        assertEquals(listOf("signature", "instructionIndex", "innerIndex", "slot", "blockTime", "side", "mint", "solLamports", "tokenRaw",
            "tokenDecimals", "venue", "earlierActionInPage", "overSignalThreshold", "signalBasis", "capturedIn"), sell.propertyNames().toList())
        assertTrue(sell["solLamports"].isString)
        assertEquals("2411141770", sell["solLamports"].asString())
        assertTrue(sell["tokenRaw"].isString)
        assertEquals(SELL_SLOT, sell["slot"].asLong())
        assertEquals("2026-10-09T23:01:45Z", sell["blockTime"].asString())
        assertEquals(1, sell["instructionIndex"].asInt())
        assertEquals(2, sell["innerIndex"].asInt())
        assertEquals(6, sell["tokenDecimals"].asInt())
        assertEquals("PUMPFUN_CURVE", sell["venue"].asString())
        assertEquals("CURVE_PAYMENT_EXCLUDING_FEES", sell["signalBasis"].asString())
    }

    @Test
    fun `a repeat within sixty seconds carries cachedAt and refresh reads the provider again`() {
        val provider = decuProvider()
        val mvc = mvc(inMemory(provider))
        body(mvc, "/api/wallets/${DECU.value}/activity", 200)
        clock.now = clock.now.plusSeconds(30)

        val cached = body(mvc, "/api/wallets/${DECU.value}/activity", 200)
        val refreshed = body(mvc, "/api/wallets/${DECU.value}/activity?refresh=true", 200)

        assertEquals("2026-10-17T10:00:00Z", cached["cachedAt"].asString())
        assertTrue(refreshed["cachedAt"].isNull)
        assertEquals(2, provider.count("getSignaturesForAddress"))
    }

    @Test
    fun `an invalid address is a 400 INVALID_ADDRESS`() {
        val body = body(mvc(decuLookup()), "/api/wallets/not-a-wallet/activity", 400)

        assertEquals("INVALID_ADDRESS", body["code"].asString())
        assertEquals(listOf("address"), body["fields"].propertyNames().toList())
    }

    @Test
    fun `a limit outside one to fifty or not a number, and a malformed before, are a 400 INVALID_REQUEST`() {
        val mvc = mvc(decuLookup())

        listOf("limit=0" to "limit", "limit=51" to "limit", "limit=abc" to "limit", "before=0OIl" to "before").forEach { (query, field) ->
            val body = body(mvc, "/api/wallets/${DECU.value}/activity?$query", 400)
            assertEquals("INVALID_REQUEST", body["code"].asString(), query)
            assertEquals(listOf(field), body["fields"].propertyNames().toList(), query)
        }
    }

    @Test
    fun `an unconfigured RPC is a 409 RPC_NOT_CONFIGURED naming only the variable`() {
        val lookup = WalletActivityLookups.create(TestSettings(endpoint = null), json, clock, null, recorder, RpcLimiterRegistry())

        val body = body(mvc(lookup), "/api/wallets/${DECU.value}/activity", 409)

        assertEquals("RPC_NOT_CONFIGURED", body["code"].asString())
        assertTrue("SOLANA_RPC_URL" in body["message"].asString())
        assertEquals(listOf("code", "message", "fields"), body.propertyNames().toList())
    }

    @Test
    fun `each unavailable reason has its status and code`() {
        val cases = listOf(
            WalletActivityUnavailable.NOT_MAINNET to 409,
            WalletActivityUnavailable.PROVIDER_UNAVAILABLE to 502,
            WalletActivityUnavailable.PROVIDER_RATE_LIMITED to 429,
            WalletActivityUnavailable.RPC_NOT_CONFIGURED to 409,
        )
        cases.forEach { (reason, status) ->
            val body = body(mvc(WalletActivityLookup { _, _ -> WalletActivityResult.Unavailable(reason, null) }), "/api/wallets/${DECU.value}/activity", status)
            assertEquals(reason.name, body["code"].asString())
            assertEquals(false, body.has("retryAfterSeconds"), reason.name)
        }
    }

    @Test
    fun `a rate limited reply with Retry-After carries a top-level retryAfterSeconds`() {
        val lookup = WalletActivityLookup { _, _ -> WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_RATE_LIMITED, Duration.ofSeconds(12)) }

        val body = body(mvc(lookup), "/api/wallets/${DECU.value}/activity", 429)

        assertEquals("PROVIDER_RATE_LIMITED", body["code"].asString())
        assertEquals(12, body["retryAfterSeconds"].asInt())
        assertTrue(body["retryAfterSeconds"].isIntegralNumber)
    }

    @Test
    fun `an empty listing is a 200 with no actions and no next cursor`() {
        val body = body(mvc(inMemory(FakeActivityRpc(emptyList(), emptyMap()))), "/api/wallets/${DECU.value}/activity", 200)

        assertEquals("[]", body["actions"].toString())
        assertTrue(body["nextBefore"].isNull)
    }

    private fun mvc(lookup: WalletActivityLookup, captures: CapturedTokens = CapturedTokens.NONE): MockMvc = MockMvcBuilders
        .standaloneSetup(WalletController(WalletActivityConsoleService(lookup, WalletActivityCache(clock), captures), WalletActivityResponseMapperImpl()))
        .setControllerAdvice(ConsoleExceptionHandler(ConsoleErrorMapperImpl()))
        .build()

    private fun decuLookup(): WalletActivityLookup = inMemory(decuProvider())

    /** The real lookup over an in-memory RPC, so no HTTP is involved. */
    private fun inMemory(provider: FakeActivityRpc): WalletActivityLookup = RecentWalletActivityService(provider)

    private fun decuProvider(): FakeActivityRpc = WalletActivityFixtures.decuAccumulator()

    /** A JSON-RPC endpoint answering from [rpc]. */
    private fun provider(rpc: FakeActivityRpc): (com.sun.net.httpserver.HttpExchange) -> Unit = { exchange ->
        val request = json.readTree(exchange.requestBody.readAllBytes())
        val params = json.convertValue(request["params"], List::class.java).map { requireNotNull(it) }
        val result = rpc.call(request["method"].asString(), params, CaptureBudget(Int.MAX_VALUE, Long.MAX_VALUE))
        LocalRpcServer.reply(exchange, 200, """{"jsonrpc":"2.0","id":1,"result":$result}""")
    }

    private fun body(mvc: MockMvc, path: String, status: Int): JsonNode {
        val response = mvc.perform(get(path)).andReturn().response
        assertEquals(status, response.status, response.contentAsString)
        return json.readTree(response.contentAsString)
    }
}
