package com.trenching.bot.console.api

import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.capture.CaptureConsoleFixture
import com.trenching.bot.console.capture.CaptureRequestException
import com.trenching.bot.console.capture.CaptureTokenFixtures
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.console.capture.WalletActivityConsoleService
import com.trenching.bot.console.mapper.CaptureResponseMapperImpl
import com.trenching.bot.console.mapper.ConsoleErrorMapperImpl
import com.trenching.bot.console.mapper.WalletActivityResponseMapperImpl
import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.WalletActivityLookup
import com.trenching.bot.walletactivity.WalletActivityPage
import com.trenching.bot.walletactivity.WalletActivityRequest
import com.trenching.bot.walletactivity.WalletActivityResult
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import com.trenching.bot.walletactivity.domain.TradeSide
import java.math.BigInteger
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode

class WalletControllerTest {
    @TempDir lateinit var root: Path

    private val clock = MutableClock(Instant.parse("2026-10-16T20:13:00Z"))
    private val wallet = WalletAddress.of(CaptureConsoleFixture.WALLET)
    private val otherMint = Base58.encode(ByteArray(32) { 3 })
    private val requests = mutableListOf<Pair<WalletActivityRequest, ApiUsageContext>>()
    private var result: WalletActivityResult = found()
    private val lookup = WalletActivityLookup { request, usage -> requests += request to usage; result }

    @Test
    fun `answers the page with exact amounts and the notes for picking a token`() {
        val fixture = CaptureConsoleFixture(root)
        fixture.createToken()

        val body = tree(fixture, controller(fixture).activity(CaptureConsoleFixture.WALLET, 20, null, false))

        assertEquals(fixture.json.readTree("""{"wallet":"${CaptureConsoleFixture.WALLET}","source":"pumpfun-user-volume-accumulator","unreadable":1,"scanned":4,
            "nextBefore":"${signature(9)}","requestsUsed":6,"cachedAt":null}"""), (body.deepCopy() as tools.jackson.databind.node.ObjectNode).apply { remove("actions") })
        val actions = (body["actions"] as Iterable<JsonNode>).toList()
        assertEquals(fixture.json.readTree("""{"signature":"${signature(1)}","instructionIndex":2,"innerIndex":5,"slot":300,"blockTime":"2026-10-16T20:00:00Z",
            "side":"BUY","mint":"${CaptureTokenFixtures.MINT}","solLamports":"1900000001","tokenRaw":"123456789012345678901","tokenDecimals":6,
            "venue":"PUMPFUN_CURVE","earlierActionInPage":true,"overSignalThreshold":true,"signalBasis":"CURVE_PAYMENT_EXCLUDING_FEES",
            "capturedIn":[{"captureId":"session-1","displayName":"Token session-1"}]}"""), actions[0])
        assertEquals(listOf(true, false, false), actions.map { it["earlierActionInPage"].asBoolean() })
        assertEquals(listOf(true, false, false), actions.map { it["overSignalThreshold"].asBoolean() })
        assertEquals(listOf(1, 1, 0), actions.map { it["capturedIn"].size() })
        assertEquals("SELL", actions[1]["side"].asString())
        assertEquals(listOf(WalletActivityRequest(wallet, 20, null) to ApiUsageContext(ApiTask.WALLET_ACTIVITY, null)), requests)
    }

    @Test
    fun `serves the same page from the cache for 60 seconds unless refresh is asked`() {
        val fixture = CaptureConsoleFixture(root)
        val controller = controller(fixture)
        val fetchedAt = clock.now

        controller.activity(CaptureConsoleFixture.WALLET, 20, null, false)
        clock.now = fetchedAt.plusSeconds(59)
        val cached = tree(fixture, controller.activity(CaptureConsoleFixture.WALLET, 20, null, false))
        val other = controller.activity(CaptureConsoleFixture.WALLET, 10, null, false)
        val refreshed = tree(fixture, controller.activity(CaptureConsoleFixture.WALLET, 20, null, true))
        clock.now = fetchedAt.plusSeconds(120)
        controller.activity(CaptureConsoleFixture.WALLET, 20, null, false)

        assertEquals(fetchedAt.toString(), cached["cachedAt"].asString())
        assertEquals(null, other.cachedAt)
        assertEquals(true, refreshed["cachedAt"].isNull)
        assertEquals(listOf(20, 10, 20, 20), requests.map { it.first.limit })
    }

    @Test
    fun `maps every unavailable reason to its status and never caches it`() {
        val fixture = CaptureConsoleFixture(root)
        val handler = CaptureExceptionHandler(ConsoleErrorMapperImpl(), CaptureResponseMapperImpl())
        val controller = controller(fixture)

        fun failure(reason: WalletActivityUnavailable, retryAfter: Duration? = null): Pair<Int, JsonNode> {
            result = WalletActivityResult.Unavailable(reason, retryAfter)
            val exception = runCatching { controller.activity(CaptureConsoleFixture.WALLET, 20, null, false) }.exceptionOrNull() as CaptureRequestException
            val response = handler.capture(exception)
            return response.statusCode.value() to tree(fixture, requireNotNull(response.body))
        }

        val limited = failure(WalletActivityUnavailable.PROVIDER_RATE_LIMITED, Duration.ofSeconds(12))
        assertEquals(429, limited.first)
        assertEquals(fixture.json.readTree("""{"code":"PROVIDER_RATE_LIMITED","message":"The RPC provider is rate limiting requests","fields":{},"retryAfterSeconds":12}"""), limited.second)
        assertFalse(failure(WalletActivityUnavailable.PROVIDER_RATE_LIMITED).second.has("retryAfterSeconds"))
        val notConfigured = failure(WalletActivityUnavailable.RPC_NOT_CONFIGURED)
        assertEquals(409 to "RPC_NOT_CONFIGURED", notConfigured.first to notConfigured.second["code"].asString())
        assertEquals("SOLANA_RPC_URL is not configured for the backend", notConfigured.second["message"].asString())
        assertEquals(409 to "NOT_MAINNET", failure(WalletActivityUnavailable.NOT_MAINNET).let { it.first to it.second["code"].asString() })
        assertEquals(502 to "PROVIDER_UNAVAILABLE", failure(WalletActivityUnavailable.PROVIDER_UNAVAILABLE).let { it.first to it.second["code"].asString() })
        assertEquals(5, requests.size)
    }

    @Test
    fun `rejects a bad address, limit or cursor before any provider request`() {
        val fixture = CaptureConsoleFixture(root)
        val controller = controller(fixture)

        fun rejected(address: String, limit: Int, before: String?): CaptureRequestException =
            runCatching { controller.activity(address, limit, before, false) }.exceptionOrNull() as CaptureRequestException

        assertEquals(Triple(400, "INVALID_ADDRESS", mapOf("address" to "must be a base58 public wallet address")), rejected("not-a-wallet", 20, null).let { Triple(it.status, it.code, it.fields) })
        assertEquals(Triple(400, "INVALID_REQUEST", setOf("limit")), rejected(CaptureConsoleFixture.WALLET, 0, null).let { Triple(it.status, it.code, it.fields.keys) })
        assertEquals(setOf("limit"), rejected(CaptureConsoleFixture.WALLET, 51, null).fields.keys)
        assertEquals(setOf("before"), rejected(CaptureConsoleFixture.WALLET, 20, "0OIl").fields.keys)
        assertEquals(emptyList(), requests)
    }

    private fun controller(fixture: CaptureConsoleFixture) =
        WalletController(WalletActivityConsoleService(lookup, fixture.store, clock), WalletActivityResponseMapperImpl())

    private fun tree(fixture: CaptureConsoleFixture, value: Any): JsonNode = fixture.json.readTree(fixture.json.writeValueAsString(value))

    private fun found(): WalletActivityResult = WalletActivityResult.Found(WalletActivityPage(
        wallet = wallet,
        actions = listOf(
            action(1, 300, TradeSide.BUY, CaptureTokenFixtures.MINT, 1_900_000_001),
            action(2, 200, TradeSide.SELL, CaptureTokenFixtures.MINT, 1_900_000_000),
            action(3, 100, TradeSide.BUY, otherMint, 5),
        ),
        unreadable = 1,
        scanned = 4,
        nextBefore = TransactionSignature.of(signature(9)),
        requestsUsed = 6,
    ))

    private fun action(index: Int, slot: Long, side: TradeSide, mint: String, lamports: Long) = WalletAction(
        signature = TransactionSignature.of(signature(index)),
        instructionIndex = 2,
        innerIndex = 5,
        slot = Slot.of(slot),
        blockTime = Instant.parse("2026-10-16T20:00:00Z"),
        side = side,
        mint = MintAddress.of(mint),
        solLamports = BigInteger.valueOf(lamports),
        tokenRaw = BigInteger("123456789012345678901"),
    )

    private fun signature(index: Int): String = Base58.encode(ByteArray(64) { position -> if (position == 0) index.toByte() else 9 })
}
