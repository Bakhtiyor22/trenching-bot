package com.trenching.bot.walletactivity

import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.chaincapture.CaptureBudgetException
import com.trenching.bot.chaincapture.CaptureCategory
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.walletactivity.WalletActivityFixtures.ACCUMULATOR
import com.trenching.bot.walletactivity.WalletActivityFixtures.BUY_EVENT_INNER
import com.trenching.bot.walletactivity.WalletActivityFixtures.BUY_EVENT_INSTRUCTION
import com.trenching.bot.walletactivity.WalletActivityFixtures.BUY_LAMPORTS
import com.trenching.bot.walletactivity.WalletActivityFixtures.BUY_MINT
import com.trenching.bot.walletactivity.WalletActivityFixtures.BUY_SIGNATURE
import com.trenching.bot.walletactivity.WalletActivityFixtures.BUY_SLOT
import com.trenching.bot.walletactivity.WalletActivityFixtures.BUY_TOKENS
import com.trenching.bot.walletactivity.WalletActivityFixtures.DECU
import com.trenching.bot.walletactivity.WalletActivityFixtures.NON_SOL_SIGNATURE
import com.trenching.bot.walletactivity.WalletActivityFixtures.NON_SOL_SLOT
import com.trenching.bot.walletactivity.WalletActivityFixtures.SELL_LAMPORTS
import com.trenching.bot.walletactivity.WalletActivityFixtures.SELL_MINT
import com.trenching.bot.walletactivity.WalletActivityFixtures.SELL_SIGNATURE
import com.trenching.bot.walletactivity.WalletActivityFixtures.SELL_SLOT
import com.trenching.bot.walletactivity.WalletActivityFixtures.V3_MINT
import com.trenching.bot.walletactivity.WalletActivityFixtures.V3_SIGNATURE
import com.trenching.bot.walletactivity.WalletActivityFixtures.V3_SLOT
import com.trenching.bot.walletactivity.WalletActivityFixtures.entry
import com.trenching.bot.walletactivity.WalletActivityFixtures.signature
import com.trenching.bot.walletactivity.application.RecentWalletActivityService
import com.trenching.bot.walletactivity.domain.TradeSide
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class RecentWalletActivityServiceTest {
    private val usage = ApiUsageContext(ApiTask.WALLET_ACTIVITY, null)
    private val thirdBuySignature = WalletActivityFixtures.THIRD_BUY_SIGNATURE

    @Test
    fun `reads Decu's accumulator listing into buys and sells newest first, skipping the failed entry and counting the PumpSwap one`() {
        val rpc = decuRpc()

        val page = found(service(rpc).recent(request(20), usage))

        assertEquals(listOf(V3_SIGNATURE, SELL_SIGNATURE, thirdBuySignature, BUY_SIGNATURE), page.actions.map { it.signature.value })
        assertEquals(listOf(TradeSide.BUY, TradeSide.SELL, TradeSide.BUY, TradeSide.BUY), page.actions.map { it.side })
        assertEquals(listOf(V3_MINT, SELL_MINT, BUY_MINT, BUY_MINT), page.actions.map { it.mint.value })
        assertEquals(1, page.unreadable)
        assertEquals(6, page.scanned)
        assertNull(page.nextBefore)
        assertEquals(7, page.requestsUsed)
        assertEquals(DECU, page.wallet)
        assertEquals("pumpfun-user-volume-accumulator", page.source)
        assertEquals(listOf(ACCUMULATOR), rpc.calls.filter { it.first == ChainCaptureFormat.GET_SIGNATURES }.map { it.second.first() })
        assertEquals(setOf(V3_SIGNATURE, SELL_SIGNATURE, NON_SOL_SIGNATURE, thirdBuySignature, BUY_SIGNATURE), rpc.fetched().toSet())
        assertEquals(5, rpc.count(ChainCaptureFormat.GET_TRANSACTION))
    }

    @Test
    fun `an action carries the event's identity, amounts, slot and block time`() {
        val page = found(service(rpc(listOf(entry(BUY_SIGNATURE, BUY_SLOT)), WalletActivityFixtures.buy())).recent(request(20), usage))

        assertEquals(WalletAction(
            signature = TransactionSignature.of(BUY_SIGNATURE),
            instructionIndex = BUY_EVENT_INSTRUCTION,
            innerIndex = BUY_EVENT_INNER,
            slot = Slot.of(BUY_SLOT),
            blockTime = Instant.ofEpochSecond(1791049629),
            side = TradeSide.BUY,
            mint = MintAddress.of(BUY_MINT),
            solLamports = BUY_LAMPORTS,
            tokenRaw = BUY_TOKENS,
            tokenDecimals = 6,
            venue = "PUMPFUN_CURVE",
        ), page.actions.single())
    }

    @Test
    fun `a sell keeps its curve proceeds as solLamports`() {
        val page = found(service(rpc(listOf(entry(SELL_SIGNATURE, SELL_SLOT)), WalletActivityFixtures.sell())).recent(request(20), usage))

        assertEquals(TradeSide.SELL to SELL_LAMPORTS, page.actions.single().let { it.side to it.solLamports })
    }

    @Test
    fun `another user's trade event yields no action and makes the transaction unreadable`() {
        val other = WalletActivityFixtures.withEventBytes(WalletActivityFixtures.buy()) { bytes ->
            ByteArray(32) { 7 }.copyInto(bytes, WalletActivityFixtures.EVENT_USER_OFFSET)
        }

        val page = found(service(rpc(listOf(entry(BUY_SIGNATURE, BUY_SLOT)), other)).recent(request(20), usage))

        assertEquals(emptyList(), page.actions)
        assertEquals(1, page.unreadable)
        assertEquals(1, page.scanned)
    }

    @Test
    fun `two wallet trade events in one transaction are two actions in instruction order`() {
        val page = found(service(rpc(listOf(entry(BUY_SIGNATURE, BUY_SLOT)), WalletActivityFixtures.twoEventBuy())).recent(request(20), usage))

        assertEquals(listOf(BUY_EVENT_INNER, BUY_EVENT_INNER + 1), page.actions.map { it.innerIndex })
        assertEquals(setOf(BUY_EVENT_INSTRUCTION), page.actions.map { it.instructionIndex }.toSet())
        assertEquals(0, page.unreadable)
    }

    @Test
    fun `a non SOL quoted buy, an unretained transaction and an unsupported version are unreadable, never guessed`() {
        val unretained = signature(4)
        val unsupported = signature(5)
        val unsupportedOnly = FakeActivityRpc(listOf(entry(unsupported, 10)), emptyMap()).apply {
            failures[ChainCaptureFormat.GET_TRANSACTION] = { CaptureProviderException(CaptureCategory.UNSUPPORTED_VERSION) }
        }
        val readable = FakeActivityRpc(listOf(entry(NON_SOL_SIGNATURE, NON_SOL_SLOT), entry(unretained, NON_SOL_SLOT - 1)),
            mapOf(NON_SOL_SIGNATURE to WalletActivityFixtures.nonSolBuy()))

        assertEquals(0 to 2, found(service(readable).recent(request(20), usage)).let { it.actions.size to it.unreadable })
        assertEquals(0 to 1, found(service(unsupportedOnly).recent(request(20), usage)).let { it.actions.size to it.unreadable })
    }

    @Test
    fun `an empty listing is an empty page with no next cursor`() {
        val page = found(service(FakeActivityRpc(emptyList(), emptyMap())).recent(request(20), usage))

        assertEquals(emptyList(), page.actions)
        assertEquals(0, page.scanned)
        assertEquals(0, page.unreadable)
        assertNull(page.nextBefore)
        assertEquals(2, page.requestsUsed)
    }

    @Test
    fun `a before signature the provider does not know gives an empty page`() {
        val page = found(service(decuRpc()).recent(request(20, before = TransactionSignature.of(signature(42))), usage))

        assertEquals(emptyList(), page.actions)
        assertNull(page.nextBefore)
    }

    @Test
    fun `a page stops at its limit and its next cursor continues right after the last entry scanned`() {
        val rpc = decuRpc()
        val service = service(rpc)

        val first = found(service.recent(request(1), usage))
        val second = found(service.recent(request(1, before = first.nextBefore), usage))
        val third = found(service.recent(request(1, before = second.nextBefore), usage))

        assertEquals(listOf(V3_SIGNATURE), first.actions.map { it.signature.value })
        assertEquals(V3_SIGNATURE, first.nextBefore?.value)
        assertEquals(listOf(SELL_SIGNATURE), second.actions.map { it.signature.value })
        assertEquals(SELL_SIGNATURE, second.nextBefore?.value)
        assertEquals(2, third.scanned, "the failed entry is scanned on the way to the next successful one")
        assertEquals(1, third.unreadable)
        assertEquals(NON_SOL_SIGNATURE, third.nextBefore?.value)
        assertEquals(listOf(null, V3_SIGNATURE, SELL_SIGNATURE), rpc.listingOptions().map { it["before"] })
    }

    @Test
    fun `a full listing page keeps a next cursor even when every entry was scanned`() {
        val listing = (1..100).map { entry(signature(100 + it), 1_000L - it, failed = true) }

        val page = found(service(FakeActivityRpc(listing, emptyMap())).recent(request(20), usage))

        assertEquals(100, page.scanned)
        assertEquals(signature(200), page.nextBefore?.value)
    }

    @Test
    fun `the listing asks for three entries per wanted transaction and never fewer than one hundred`() {
        val rpc = FakeActivityRpc(emptyList(), emptyMap())
        val service = service(rpc)

        service.recent(request(20), usage)
        service.recent(request(50), usage)

        assertEquals(listOf(100, 150), rpc.listingOptions().map { it["limit"] })
        assertEquals(listOf("confirmed", "confirmed"), rpc.listingOptions().map { it["commitment"] })
    }

    @Test
    fun `the budget allows the listing, at most limit transactions and the first genesis check, under the caller's usage context`() {
        val rpc = decuRpc()
        val service = service(rpc)

        val first = found(service.recent(request(2), usage))
        val second = found(service.recent(request(2), usage))

        assertEquals(1, rpc.count(ChainCaptureFormat.GET_GENESIS), "genesis is checked once per lifetime")
        assertEquals(4, first.requestsUsed)
        assertEquals(3, second.requestsUsed)
        assertEquals(listOf(4, 3), rpc.budgets.distinct().map { it.maxRequests })
        assertTrue(rpc.budgets.all { it.usage == usage && it.operationTimeout == Duration.ofSeconds(30) })
    }

    @Test
    fun `a request beyond the bound stops the lookup as provider unavailable`() {
        val rpc = decuRpc().apply { requestsPerCall = 2 }

        val result = service(rpc).recent(request(2), usage)

        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_UNAVAILABLE, null), result)
    }

    @Test
    fun `each provider failure maps to its unavailable reason`() {
        val cases = mapOf(
            CaptureProviderException("NOT_CONFIGURED") to WalletActivityResult.Unavailable(WalletActivityUnavailable.RPC_NOT_CONFIGURED, null),
            CaptureProviderException(CaptureCategory.RATE_LIMITED, Duration.ofSeconds(12)) to
                WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_RATE_LIMITED, Duration.ofSeconds(12)),
            CaptureProviderException("UNAVAILABLE") to WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_UNAVAILABLE, null),
            CaptureProviderException("IO") to WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_UNAVAILABLE, null),
            CaptureBudgetException("DEADLINE") to WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_UNAVAILABLE, null),
        )
        cases.forEach { (failure, expected) ->
            val rpc = decuRpc().apply { failures[ChainCaptureFormat.GET_SIGNATURES] = { failure } }
            assertEquals<WalletActivityResult>(expected, service(rpc).recent(request(20), usage), failure.message)
        }
    }

    @Test
    fun `a rate limited transaction read stops the whole page`() {
        val rpc = decuRpc().apply { failures[ChainCaptureFormat.GET_TRANSACTION] = { CaptureProviderException(CaptureCategory.RATE_LIMITED, Duration.ofSeconds(3)) } }

        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_RATE_LIMITED, Duration.ofSeconds(3)),
            service(rpc).recent(request(20), usage))
    }

    @Test
    fun `a budget that runs out after a rate limited reply keeps that reply's Retry-After`() {
        val decu = decuRpc()
        val rpc = SolanaReadRpc { method, params, budget ->
            if (method != ChainCaptureFormat.GET_TRANSACTION) return@SolanaReadRpc decu.call(method, params, budget)
            budget.counters.rateLimited(Duration.ofSeconds(4))
            throw CaptureBudgetException("REQUESTS")
        }

        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_RATE_LIMITED, Duration.ofSeconds(4)),
            RecentWalletActivityService(rpc).recent(request(20), usage))
    }

    @Test
    fun `a malformed listing is provider unavailable`() {
        val malformed = FakeActivityRpc(listOf(WalletActivityFixtures.mapper.createObjectNode().put("slot", 1)), emptyMap())

        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_UNAVAILABLE, null), service(malformed).recent(request(20), usage))
    }

    @Test
    fun `a listing that gets newer is provider unavailable`() {
        val unordered = FakeActivityRpc(listOf(entry(BUY_SIGNATURE, BUY_SLOT), entry(V3_SIGNATURE, V3_SLOT)), emptyMap())

        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_UNAVAILABLE, null), service(unordered).recent(request(20), usage))
    }

    @Test
    fun `a non mainnet endpoint is refused before any listing and checked again next time`() {
        val rpc = decuRpc(genesis = "devnet-genesis")
        val service = service(rpc)

        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.NOT_MAINNET, null), service.recent(request(20), usage))
        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.NOT_MAINNET, null), service.recent(request(20), usage))
        assertEquals(listOf(ChainCaptureFormat.GET_GENESIS, ChainCaptureFormat.GET_GENESIS), rpc.calls.map { it.first })
    }

    @Test
    fun `the request rejects a limit outside one to fifty`() {
        listOf(0, 51).forEach { limit ->
            val failure = runCatching { request(limit) }.exceptionOrNull()
            assertIs<IllegalArgumentException>(failure, "limit=$limit")
        }
    }

    private fun decuRpc(genesis: String = ChainCaptureFormat.MAINNET_GENESIS): FakeActivityRpc = WalletActivityFixtures.decuAccumulator(genesis)

    private fun rpc(listing: List<tools.jackson.databind.node.ObjectNode>, transaction: JsonNode): FakeActivityRpc =
        FakeActivityRpc(listing, mapOf(listing.first().path("signature").asString() to transaction))

    private fun service(rpc: FakeActivityRpc) = RecentWalletActivityService(rpc, concurrency = 2)

    private fun request(limit: Int, before: TransactionSignature? = null) = WalletActivityRequest(DECU, limit, before)

    private fun found(result: WalletActivityResult): WalletActivityPage = assertIs<WalletActivityResult.Found>(result).page
}
