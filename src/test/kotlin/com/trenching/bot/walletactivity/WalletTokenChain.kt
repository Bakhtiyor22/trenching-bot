package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.Base58
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * A fake read-only RPC for token-account captures: per-address newest-first listings paged like `getSignaturesForAddress`,
 * and transactions by signature. Built from the recorded Decu buy and minimal `jsonParsed` transactions; no provider is called.
 */
internal class WalletTokenChain(private val mapper: JsonMapper) {
    data class Entry(val signature: String, val slot: Long, val failed: Boolean = false)

    data class Call(val method: String, val subject: String?, val before: String?)

    val calls = mutableListOf<Call>()
    val listings = mutableMapOf<String, List<Entry>>()
    val transactions = mutableMapOf<String, JsonNode>()
    var owner: String? = TOKEN_2022

    fun rpc() = SolanaReadRpc { method, params, budget ->
        budget.request()
        val options = params.getOrNull(1) as? Map<*, *>
        calls += Call(method, params.firstOrNull() as? String, options?.get("before") as? String)
        when (method) {
            "getGenesisHash" -> mapper.readTree("\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\"")
            "getAccountInfo" -> mapper.readTree(owner?.let {
                """{"context":{"slot":1},"value":{"data":["","base64"],"executable":false,"lamports":1461600,"owner":"$it"}}"""
            } ?: """{"context":{"slot":1},"value":null}""")
            "getSignaturesForAddress" -> page(params[0] as String, options?.get("before") as? String)
            "getTransaction" -> transactions[params[0] as String] ?: mapper.nullNode()
            else -> error("Unexpected method $method")
        }
    }

    fun listing(address: String, vararg entries: Entry) {
        listings[address] = entries.toList()
    }

    fun add(vararg transaction: ObjectNode) = transaction.forEach { transactions[signatureOf(it)] = it }

    /** Calls other than the genesis check each invocation repeats. */
    fun work(): List<Call> = calls.filter { it.method != "getGenesisHash" }

    private fun page(address: String, before: String?): ArrayNode {
        val listing = listings[address].orEmpty()
        val start = when (before) {
            null -> 0
            else -> listing.indexOfFirst { it.signature == before }.takeIf { it >= 0 }?.plus(1)
                ?: slotOf(before).let { slot -> listing.indexOfFirst { it.slot < slot }.takeIf { it >= 0 } ?: listing.size }
        }
        val page = mapper.createArrayNode()
        listing.drop(start).take(PAGE_SIZE).forEach { entry ->
            page.addObject().put("signature", entry.signature).put("slot", entry.slot).apply {
                if (entry.failed) putObject("err").put("InstructionError", "failed") else putNull("err")
            }
        }
        return page
    }

    private fun slotOf(signature: String): Long = listings.values.flatten().firstOrNull { it.signature == signature }?.slot
        ?: transactions[signature]?.path("slot")?.asLong()
        ?: error("Unknown cursor signature $signature")

    fun buy(signature: String = BUY, slot: Long = BUY_SLOT): ObjectNode {
        val transaction = javaClass.getResourceAsStream("/walletactivity/decoder/pumpfun-buy.json")
            .use { mapper.readTree(requireNotNull(it)) } as ObjectNode
        (transaction.path("transaction").path("signatures") as ArrayNode).set(0, mapper.nodeFactory.stringNode(signature))
        return transaction.put("slot", slot)
    }

    /** A successful transaction with [keys] (address to signer) and the given instructions and token balances. */
    fun transaction(
        signature: String,
        slot: Long,
        keys: List<Pair<String, Boolean>>,
        instructions: List<ObjectNode> = emptyList(),
        blockTime: Long? = BLOCK_TIME,
        preTokenBalances: List<ObjectNode> = emptyList(),
        postTokenBalances: List<ObjectNode> = emptyList(),
    ): ObjectNode = mapper.createObjectNode().apply {
        put("slot", slot)
        if (blockTime == null) putNull("blockTime") else put("blockTime", blockTime)
        put("version", 0)
        putObject("transaction").apply {
            putArray("signatures").add(signature)
            putObject("message").apply {
                val accountKeys = putArray("accountKeys")
                keys.forEach { (key, signer) -> accountKeys.addObject().put("pubkey", key).put("signer", signer).put("writable", true).put("source", "transaction") }
                putArray("instructions").addAll(instructions)
                put("recentBlockhash", SYSTEM_PROGRAM)
            }
        }
        putObject("meta").apply {
            putNull("err")
            put("fee", 5000)
            putArray("preBalances").apply { keys.forEach { add(1_000_000) } }
            putArray("postBalances").apply { keys.forEach { add(1_000_000) } }
            putArray("innerInstructions")
            putArray("preTokenBalances").addAll(preTokenBalances)
            putArray("postTokenBalances").addAll(postTokenBalances)
        }
    }

    fun create(mint: String = MINT, discriminator: ByteArray = PumpFunLayout.CREATE_DISCRIMINATOR): ObjectNode = mapper.createObjectNode().apply {
        put("programId", PUMP_FUN)
        putArray("accounts").add(mint).add(CREATOR)
        put("data", Base58.encode(discriminator + ByteArray(8)))
    }

    fun tokenBalance(accountIndex: Int, owner: String, amount: Long, mint: String = MINT): ObjectNode = mapper.createObjectNode().apply {
        put("accountIndex", accountIndex)
        put("mint", mint)
        put("owner", owner)
        put("programId", TOKEN_2022)
        putObject("uiTokenAmount").put("amount", amount.toString()).put("decimals", 6)
    }

    private fun signatureOf(transaction: JsonNode): String = transaction.path("transaction").path("signatures").path(0).asString()

    companion object {
        const val PAGE_SIZE = 1000
        const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val TOKEN_ACCOUNT = "4CnXbpxS5UHLmeEnj9MJrAGdQCSZpDotTCAva85zQUYi"
        const val PUMP_ACCUMULATOR = "Hj2hjN1EAe5pv676kUMf14zbj1ioWEHaiukbHDqw2P3D"
        const val PUMPSWAP_ACCUMULATOR = "GhhDLG4uBDVSnEAXwBpzfm4JR9E9HweSAhrCCGPjMvgp"
        const val TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
        const val PUMP_FUN = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"
        const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
        const val CREATOR = "CKT5rabpEHRgvHU1M1DKeN1b1MhJnRr8ZLkHAajSCEmZ"
        const val OTHER = "W9TBJ1oCrSfdvzNVi8FHJha2bmAifAApUpnGTdjrtUq"
        const val OTHER_TOKEN_ACCOUNT = "2ApLdwLrGayEmxgpLX9BTR47Q2QprfMg5SpjrLeaK8s7"
        const val BUY = "35Vnpu9DSwADxy77FBQV8seGitMXuQRPB575AKXv1Jhh3NQSuZRKtuppUkiyLkYszxxi9HaKcmNN7fywqJ9R3mNA"
        const val BUY_SLOT = 453004996L
        const val BLOCK_TIME = 1791049000L

        /** A distinct valid signature for each [index]. */
        fun signature(index: Int): String = Base58.encode(ByteArray(60) + java.nio.ByteBuffer.allocate(4).putInt(index + 1).array())
    }
}
