package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.Base58
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import com.trenching.bot.protocol.pumpfun.PumpSwapLayout
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryListing
import java.math.BigInteger
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Decu's captured Pump.fun transactions (`src/test/resources/walletactivity/decoder`) and the accumulator listing they
 * would appear in. Every derived transaction is a copy with its identity or one event field rewritten.
 */
internal object WalletActivityFixtures {
    val mapper: JsonMapper = JsonMapper.builder().build()
    val DECU: WalletAddress = WalletAddress.of("4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9")
    val ACCUMULATOR: String = WalletHistoryListing.pumpAccumulator(DECU)

    const val BUY_SIGNATURE = "35Vnpu9DSwADxy77FBQV8seGitMXuQRPB575AKXv1Jhh3NQSuZRKtuppUkiyLkYszxxi9HaKcmNN7fywqJ9R3mNA"
    const val BUY_SLOT = 453004996L
    const val BUY_MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
    val BUY_LAMPORTS = BigInteger("244444444")
    val BUY_TOKENS = BigInteger("1238520682656")
    const val BUY_EVENT_INSTRUCTION = 1
    const val BUY_EVENT_INNER = 7

    const val V3_SIGNATURE = "26jumCkdDKmagU9CTARuecYGp2zofCQm5kPWA1sXijWMtLMHB5tZJo2Vn478zRmysZ81izXzM9YAX3qevYAatJWq"
    const val V3_SLOT = 455091775L
    const val V3_MINT = "6LChGSaKeBUoZGkiNc8z3ZB2rttv7pg1XBPaQJ2rpump"

    const val SELL_SIGNATURE = "1pt9oSsGp1B98cTPgkXm9aiZuynAUqhc5y8m8qRCvqd5LeWT88izhPyxbwArrDoH3ooE78ooqfistv3cHSjew9R"
    const val SELL_SLOT = 455033484L
    const val SELL_MINT = "2kVaEheJmQ87h3c6xqzNWitkPfxELEHf9HbaVayRpump"
    val SELL_LAMPORTS = BigInteger("2411141770")

    const val NON_SOL_SIGNATURE = "1JMNs2FbojT2WcaCoED8Kb9RPTxVsu4hatGYX8WQ3n4q2f1xwo8jfvcbqgA1sTGTVurVVT2Let8u6JP41KKTAX5"
    const val NON_SOL_SLOT = 455032676L

    val THIRD_BUY_SIGNATURE: String = signature(3)
    const val THIRD_BUY_SLOT = 454000000L
    val FAILED_SIGNATURE: String = signature(9)

    const val EVENT_SOL_AMOUNT_OFFSET = PumpFunLayout.EVENT_SOL_AMOUNT_OFFSET
    const val EVENT_USER_OFFSET = PumpFunLayout.EVENT_USER_OFFSET

    /**
     * Decu's accumulator listing, newest first: the `buy_exact_quote_in_v3` buy, the `sell_v3`, a failed entry, a
     * PumpSwap-routed transaction, the legacy buy sent again later, and the legacy buy. Four actions, one unreadable, six scanned.
     */
    fun decuAccumulator(genesis: String = ChainCaptureFormat.MAINNET_GENESIS): FakeActivityRpc = FakeActivityRpc(
        listOf(
            entry(V3_SIGNATURE, V3_SLOT),
            entry(SELL_SIGNATURE, SELL_SLOT),
            entry(FAILED_SIGNATURE, SELL_SLOT - 100, failed = true),
            entry(NON_SOL_SIGNATURE, NON_SOL_SLOT),
            entry(THIRD_BUY_SIGNATURE, THIRD_BUY_SLOT),
            entry(BUY_SIGNATURE, BUY_SLOT),
        ),
        mapOf(
            V3_SIGNATURE to v3Buy(),
            SELL_SIGNATURE to sell(),
            NON_SOL_SIGNATURE to pumpSwap(nonSolBuy()),
            THIRD_BUY_SIGNATURE to relisted(buy(), THIRD_BUY_SIGNATURE, THIRD_BUY_SLOT),
            BUY_SIGNATURE to buy(),
        ),
        genesis,
    )

    fun buy(): ObjectNode = fixture("pumpfun-buy.json")
    fun v3Buy(): ObjectNode = fixture("pumpfun-buy-exact-quote-in-v3.json")
    fun sell(): ObjectNode = fixture("pumpfun-sell-v3.json")
    fun nonSolBuy(): ObjectNode = fixture("pumpfun-buy-exact-quote-in-v3-non-sol.json")

    /** A distinct, valid 64-byte signature for a derived transaction. */
    fun signature(seed: Int): String = Base58.encode(ByteArray(64) { (seed + it % 7 + 1).toByte() })

    /** [transaction] as if it had been sent again under [signature] at [slot]. */
    fun relisted(transaction: ObjectNode, signature: String, slot: Long): ObjectNode = transaction.deepCopy().apply {
        put("slot", slot)
        (path("transaction").path("signatures") as ArrayNode).set(0, mapper.nodeFactory.stringNode(signature))
    }

    /** [transaction] with every Pump.fun instruction attributed to PumpSwap, so no Pump.fun `TradeEvent` remains. */
    fun pumpSwap(transaction: ObjectNode): ObjectNode = transaction.deepCopy().apply {
        (path("transaction").path("message").path("instructions") as ArrayNode).forEach(::toPumpSwap)
        path("meta").path("innerInstructions").forEach { group -> group.path("instructions").forEach(::toPumpSwap) }
    }

    /** The buy fixture with a second copy of its `TradeEvent` right after the first, in the same top-level instruction. */
    fun twoEventBuy(): ObjectNode = buy().apply {
        val instructions = eventGroup(this)
        instructions.insert(BUY_EVENT_INNER + 1, instructions.path(BUY_EVENT_INNER).deepCopy())
    }

    fun withEventBytes(transaction: ObjectNode, change: (ByteArray) -> Unit): ObjectNode = transaction.deepCopy().apply {
        val event = pumpFunEvents(this).single()
        val bytes = requireNotNull(Base58.decode(event.path("data").asString()))
        change(bytes)
        event.put("data", Base58.encode(bytes))
    }

    fun writeU64(bytes: ByteArray, offset: Int, value: BigInteger) {
        value.toByteArray().reversedArray().copyOf(PumpFunLayout.U64_BYTES).copyInto(bytes, offset)
    }

    fun entry(signature: String, slot: Long, failed: Boolean = false): ObjectNode = mapper.createObjectNode().apply {
        put("signature", signature)
        put("slot", slot)
        if (failed) set("err", mapper.createObjectNode().put("InstructionError", "Custom")) else putNull("err")
        putNull("memo")
    }

    private fun fixture(name: String): ObjectNode =
        javaClass.getResourceAsStream("/walletactivity/decoder/$name").use { mapper.readTree(requireNotNull(it)) as ObjectNode }

    private fun eventGroup(transaction: ObjectNode): ArrayNode =
        transaction.path("meta").path("innerInstructions").single { it.path("index").asInt() == BUY_EVENT_INSTRUCTION }.path("instructions") as ArrayNode

    private fun pumpFunEvents(transaction: ObjectNode): List<ObjectNode> =
        transaction.path("meta").path("innerInstructions").flatMap { it.path("instructions") }
            .filter { it.path("programId").asString() == PumpFunLayout.PROGRAM_ID && isTradeEvent(it) }
            .map { it as ObjectNode }

    private fun isTradeEvent(instruction: JsonNode): Boolean {
        val bytes = Base58.decode(instruction.path("data").asString()) ?: return false
        return bytes.size >= PumpFunLayout.TRADE_EVENT_PREFIX.size &&
            PumpFunLayout.TRADE_EVENT_PREFIX.indices.all { bytes[it] == PumpFunLayout.TRADE_EVENT_PREFIX[it] }
    }

    private fun toPumpSwap(instruction: JsonNode) {
        if (instruction.path("programId").asString() == PumpFunLayout.PROGRAM_ID) (instruction as ObjectNode).put("programId", PumpSwapLayout.PROGRAM_ID)
    }
}

/**
 * An RPC that answers from an in-memory accumulator listing and transaction map, charging the budget once per call (or
 * [requestsPerCall] times, to stand for retries). [failures] makes a method throw instead of answering.
 */
internal class FakeActivityRpc(
    private val listing: List<ObjectNode>,
    private val transactions: Map<String, JsonNode>,
    var genesis: String = ChainCaptureFormat.MAINNET_GENESIS,
) : SolanaReadRpc {
    val calls: MutableList<Pair<String, List<Any>>> = java.util.Collections.synchronizedList(mutableListOf())
    val budgets: MutableList<CaptureBudget> = java.util.Collections.synchronizedList(mutableListOf())
    val failures: MutableMap<String, () -> Exception> = java.util.concurrent.ConcurrentHashMap()
    var requestsPerCall = 1

    override fun call(method: String, params: List<Any>, budget: CaptureBudget): JsonNode {
        repeat(requestsPerCall) { budget.request() }
        calls += method to params
        budgets += budget
        failures[method]?.let { throw it() }
        return when (method) {
            ChainCaptureFormat.GET_GENESIS -> WalletActivityFixtures.mapper.nodeFactory.stringNode(genesis)
            ChainCaptureFormat.GET_SIGNATURES -> page(params)
            ChainCaptureFormat.GET_TRANSACTION -> transactions[params.first() as String] ?: WalletActivityFixtures.mapper.nullNode()
            else -> error("Unexpected method $method")
        }
    }

    fun count(method: String): Int = calls.count { it.first == method }

    fun fetched(): List<String> = calls.filter { it.first == ChainCaptureFormat.GET_TRANSACTION }.map { it.second.first() as String }

    fun listingOptions(): List<Map<*, *>> = calls.filter { it.first == ChainCaptureFormat.GET_SIGNATURES }.map { it.second[1] as Map<*, *> }

    private fun page(params: List<Any>): JsonNode {
        val options = params[1] as Map<*, *>
        val limit = options["limit"] as Int
        val before = options["before"] as String?
        val start = if (before == null) 0 else listing.indexOfFirst { it.path("signature").asString() == before }.let { if (it < 0) listing.size else it + 1 }
        return WalletActivityFixtures.mapper.createArrayNode().apply { listing.drop(start).take(limit).forEach { add(it) } }
    }
}
