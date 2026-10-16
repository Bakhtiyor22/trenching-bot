package com.trenching.bot.walletactivity

import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.CaptureCategory
import com.trenching.bot.chaincapture.CaptureException
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.Base58
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.protocol.pumpfun.PumpFunInstruction
import com.trenching.bot.protocol.pumpfun.PumpFunInstructionReader
import com.trenching.bot.protocol.pumpfun.VolumeAccumulatorAddress
import com.trenching.bot.walletactivity.application.RecentWalletActivityService
import com.trenching.bot.walletactivity.domain.TradeSide
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

class RecentWalletActivityServiceTest {
    private val mapper = JsonMapper.builder().build()
    private val wallet = WalletAddress.of(DECU)
    private val usage = ApiUsageContext(ApiTask.WALLET_ACTIVITY, null)
    private val buy = fixture("pumpfun-buy.json")
    private val sell = fixture("pumpfun-sell-v3.json")
    private val exactQuoteIn = fixture("pumpfun-buy-exact-quote-in-v3.json")
    private val nonSol = fixture("pumpfun-buy-exact-quote-in-v3-non-sol.json")

    @Test
    fun `lists the wallet's buys and sells newest first and counts what it cannot read or skips`() {
        val failed = entry(FAILED_SIGNATURE, slot(exactQuoteIn) - 1, failed = true)
        val rpc = StubRpc((listOf(exactQuoteIn, sell, nonSol, buy).map(::entry) + failed).sortedByDescending { it.slot }, listOf(buy, sell, exactQuoteIn, nonSol))

        val page = found(service(rpc).recent(WalletActivityRequest(wallet, 20, null), usage))

        assertEquals(listOf(TradeSide.BUY, TradeSide.SELL, TradeSide.BUY), page.actions.map { it.side })
        assertEquals(listOf(signature(exactQuoteIn), signature(sell), signature(buy)), page.actions.map { it.signature.value })
        assertEquals(1, page.unreadable)
        assertEquals(5, page.scanned)
        assertNull(page.nextBefore)
        assertEquals(6, page.requestsUsed)
        assertEquals(WalletActivityPage.SOURCE, page.source)
        assertTrue(FAILED_SIGNATURE !in rpc.fetched)
        assertEquals<Any?>(VolumeAccumulatorAddress.pump(DECU), rpc.listed.single().first)
        assertEquals(Instant.ofEpochSecond(buy.path("blockTime").asLong()), page.actions.last().blockTime)
    }

    @Test
    fun `reads the curve payment and raw token amount from the wallet's own trade event`() {
        val event = PumpFunInstructionReader.read(buy).filterIsInstance<PumpFunInstruction.TradeEvent>().single()

        val action = found(service(StubRpc(listOf(entry(buy)), listOf(buy))).recent(WalletActivityRequest(wallet, 20, null), usage)).actions.single()

        assertEquals(event.solAmount, action.solLamports)
        assertEquals(event.tokenAmount, action.tokenRaw)
        assertEquals(event.mint, action.mint)
        assertEquals(event.topLevelIndex to event.innerIndex, action.instructionIndex to action.innerIndex)
        assertEquals(WalletAction.PUMPFUN_CURVE, action.venue)
    }

    @Test
    fun `another user's trade event is unreadable for this wallet and never becomes an action`() {
        val other = WalletAddress.of("1".repeat(32))

        val page = found(service(StubRpc(listOf(entry(buy)), listOf(buy))).recent(WalletActivityRequest(other, 20, null), usage))

        assertEquals(emptyList(), page.actions)
        assertEquals(1, page.unreadable)
    }

    @Test
    fun `two wallet trade events in one transaction give two actions with distinct identities`() {
        val twice = withDuplicatedTradeEvent(buy)

        val actions = found(service(StubRpc(listOf(entry(twice)), listOf(twice))).recent(WalletActivityRequest(wallet, 20, null), usage)).actions

        assertEquals(2, actions.size)
        assertEquals(1, actions.map { it.signature }.distinct().size)
        assertEquals(2, actions.map { Triple(it.signature, it.instructionIndex, it.innerIndex) }.distinct().size)
        assertTrue(requireNotNull(actions[0].innerIndex) < requireNotNull(actions[1].innerIndex))
    }

    @Test
    fun `reads at most limit successful transactions from a listing sized for the limit and continues before the last one`() {
        val listing = (0 until 120).map { entry(transactionSignature(it), 1_000L - it, failed = it % 2 == 1) }
        val rpc = StubRpc(listing, emptyList())

        val page = found(service(rpc).recent(WalletActivityRequest(wallet, 2, null), usage))

        assertEquals(WalletActivityLimits.MIN_LISTING_ENTRIES, (rpc.listed.single().second["limit"] as Int))
        assertEquals(listOf(transactionSignature(0), transactionSignature(2)), rpc.fetched)
        assertEquals(3, page.scanned)
        assertEquals(2, page.unreadable)
        assertEquals(transactionSignature(2), page.nextBefore?.value)
    }

    @Test
    fun `passes before to the listing and answers an empty page when the provider has nothing older`() {
        val rpc = StubRpc(emptyList(), emptyList())
        val before = TransactionSignature.of(transactionSignature(7))

        val page = found(service(rpc).recent(WalletActivityRequest(wallet, 20, before), usage))

        assertEquals(before.value, rpc.listed.single().second["before"])
        assertEquals(emptyList(), page.actions)
        assertEquals(0, page.scanned)
        assertNull(page.nextBefore)
    }

    @Test
    fun `checks the genesis hash once per process and charges each lookup only for its own requests`() {
        val rpc = StubRpc(listOf(entry(buy)), listOf(buy))
        val service = service(rpc)

        val first = found(service.recent(WalletActivityRequest(wallet, 20, null), usage))
        val second = found(service.recent(WalletActivityRequest(wallet, 20, null), usage))

        assertEquals(1, rpc.methods.count { it == ChainCaptureFormat.GET_GENESIS })
        assertEquals(3, first.requestsUsed)
        assertEquals(2, second.requestsUsed)
        assertEquals(listOf(usage), rpc.usages.distinct())
    }

    @Test
    fun `bounds the request budget at three attempts per transaction plus the listing and genesis check`() {
        assertEquals(152, WalletActivityLimits.maxRequests(WalletActivityLimits.MAX_LIMIT))
        val rpc = StubRpc(listOf(entry(buy)), listOf(buy))

        service(rpc).recent(WalletActivityRequest(wallet, 5, null), usage)

        assertEquals(17, rpc.maxRequests)
    }

    @Test
    fun `maps every provider failure to its unavailable reason`() {
        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_RATE_LIMITED, Duration.ofSeconds(7)),
            failing(CaptureProviderException(CaptureCategory.RATE_LIMITED, Duration.ofSeconds(7))))
        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_UNAVAILABLE, null), failing(CaptureProviderException("IO")))
        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_UNAVAILABLE, null), failing(CaptureProviderException("UNAVAILABLE")))
        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.RPC_NOT_CONFIGURED, null), failing(CaptureValidationException("ENDPOINT_TLS")))
        val notMainnet = StubRpc(emptyList(), emptyList()).apply { genesis = "1".repeat(44) }
        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.NOT_MAINNET, null),
            service(notMainnet).recent(WalletActivityRequest(wallet, 20, null), usage))
    }

    @Test
    fun `answers not configured without sending a request when no endpoint is set`() {
        val rpc = StubRpc(emptyList(), emptyList())

        val result = RecentWalletActivityService(rpcConfigured = false) { rpc }.recent(WalletActivityRequest(wallet, 20, null), usage)

        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.RPC_NOT_CONFIGURED, null), result)
        assertEquals(emptyList(), rpc.methods)
    }

    private fun failing(failure: CaptureException): WalletActivityResult =
        service(StubRpc(emptyList(), emptyList()).apply { this.failure = failure }).recent(WalletActivityRequest(wallet, 20, null), usage)

    private fun service(rpc: SolanaReadRpc) = RecentWalletActivityService(rpcConfigured = true) { rpc }

    private fun found(result: WalletActivityResult): WalletActivityPage = assertIs<WalletActivityResult.Found>(result).page

    private fun fixture(name: String): ObjectNode =
        javaClass.getResourceAsStream("/walletactivity/decoder/$name").use { mapper.readTree(requireNotNull(it)) } as ObjectNode

    private fun signature(transaction: JsonNode): String = transaction.path("transaction").path("signatures").path(0).asString()

    private fun slot(transaction: JsonNode): Long = transaction.path("slot").asLong()

    private fun entry(transaction: JsonNode): ListingEntry = entry(signature(transaction), slot(transaction))

    private fun entry(signature: String, slot: Long, failed: Boolean = false) = ListingEntry(signature, slot, failed)

    private fun transactionSignature(index: Int): String = Base58.encode(ByteArray(SIGNATURE_BYTES) { position -> if (position == 0) index.toByte() else 9 })

    /** A copy of [transaction] whose wallet trade event appears a second time in the same inner instruction group. */
    private fun withDuplicatedTradeEvent(transaction: ObjectNode): ObjectNode {
        val copy = transaction.deepCopy()
        val event = PumpFunInstructionReader.read(copy).filterIsInstance<PumpFunInstruction.TradeEvent>().single()
        val group = copy.path("meta").path("innerInstructions").first { it.path("index").asInt() == event.topLevelIndex }
        val instructions = group.path("instructions") as ArrayNode
        instructions.add(instructions[requireNotNull(event.innerIndex)].deepCopy())
        return copy
    }

    data class ListingEntry(val signature: String, val slot: Long, val failed: Boolean)

    /** Answers from fixtures and records every call, charging the budget as the HTTP transport does. */
    private inner class StubRpc(private val listing: List<ListingEntry>, transactions: List<JsonNode>) : SolanaReadRpc {
        private val bySignature = transactions.associateBy(::signature)
        var genesis = ChainCaptureFormat.MAINNET_GENESIS
        var failure: CaptureException? = null
        var maxRequests = 0
        val methods = mutableListOf<String>()
        val usages = mutableListOf<ApiUsageContext>()
        val listed = mutableListOf<Pair<Any, Map<*, *>>>()
        val fetched = mutableListOf<String>()

        override fun call(method: String, params: List<Any>, budget: CaptureBudget): JsonNode {
            budget.request()
            maxRequests = budget.maxRequests
            methods += method
            usages += budget.usage
            failure?.let { throw it }
            return when (method) {
                ChainCaptureFormat.GET_GENESIS -> mapper.readTree("\"$genesis\"")
                ChainCaptureFormat.GET_SIGNATURES -> {
                    val options = params[1] as Map<*, *>
                    listed += params[0] to options
                    mapper.createArrayNode().apply {
                        listing.take(options["limit"] as Int).forEach { entry ->
                            add(mapper.createObjectNode().put("signature", entry.signature).put("slot", entry.slot).apply {
                                if (entry.failed) putObject("err").put("InstructionError", "failed") else putNull("err")
                            })
                        }
                    }
                }
                ChainCaptureFormat.GET_TRANSACTION -> {
                    val requested = params[0] as String
                    fetched += requested
                    bySignature[requested] ?: mapper.nullNode()
                }
                else -> error("Unexpected method $method")
            }
        }
    }

    private companion object {
        const val DECU = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        const val SIGNATURE_BYTES = 64
        val FAILED_SIGNATURE: String = Base58.encode(ByteArray(SIGNATURE_BYTES) { 7 })
    }
}
