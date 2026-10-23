package com.trenching.bot.chaincapture

import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiPurpose
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class BlockTimeReadTest {
    private val mapper = JsonMapper.builder().build()
    private val budget = CaptureBudget(10, 1_000_000)

    @Test
    fun `a produced slot's block time is read with one getBlockTime`() {
        val chain = Chain(produced = mapOf(SLOT to TIME))

        assertEquals(Instant.ofEpochSecond(TIME), ChainCaptureReads.blockTime(chain.rpc, SLOT, budget))
        assertEquals(listOf("getBlockTime" to listOf<Any>(SLOT)), chain.calls)
        assertTrue("getBlockTime" in ChainCaptureFormat.READ_METHODS)
    }

    @Test
    fun `a skipped slot steps back to the newest produced block of the bounded range below it`() {
        val chain = Chain(produced = mapOf(SLOT - 7 to TIME - 3, SLOT - 2 to TIME - 1))

        assertEquals(Instant.ofEpochSecond(TIME - 1), ChainCaptureReads.blockTime(chain.rpc, SLOT, budget))
        assertEquals(listOf(
            "getBlockTime" to listOf<Any>(SLOT),
            "getBlocks" to listOf(SLOT - ChainCaptureFormat.BLOCK_TIME_LOOKBACK_SLOTS, SLOT, mapOf("commitment" to "confirmed")),
            "getBlockTime" to listOf<Any>(SLOT - 2),
        ), chain.calls)
        assertTrue("getBlocks" in ChainCaptureFormat.READ_METHODS)
    }

    @Test
    fun `no produced block within the bound or a time the provider does not have gives none`() {
        val farBelow = SLOT - ChainCaptureFormat.BLOCK_TIME_LOOKBACK_SLOTS - 1
        assertNull(ChainCaptureReads.blockTime(Chain(produced = mapOf(farBelow to TIME)).rpc, SLOT, budget))
        assertNull(ChainCaptureReads.blockTime(Chain(produced = emptyMap(), withoutTime = setOf(SLOT)).rpc, SLOT, budget))
    }

    @Test
    fun `the range below a slot near genesis starts at slot zero`() {
        val chain = Chain(produced = mapOf(0L to TIME))

        assertEquals(Instant.ofEpochSecond(TIME), ChainCaptureReads.blockTime(chain.rpc, 5, budget))
        assertEquals(listOf(0L, 5L, mapOf("commitment" to "confirmed")), chain.calls[1].second)
    }

    @Test
    fun `another provider failure is not mistaken for a skipped slot`() {
        val rpc = SolanaReadRpc { _, _, _ -> throw CaptureProviderException("RPC_ERROR") }

        val failure = assertFailsWith<CaptureProviderException> { ChainCaptureReads.blockTime(rpc, SLOT, budget) }
        assertEquals("RPC_ERROR", failure.category)
    }

    @Test
    fun `a reply that is not a whole non-negative second or a list of slots in range is refused`() {
        listOf("-1", "1.5", "\"x\"", "{}").forEach { reply ->
            val rpc = SolanaReadRpc { _, _, _ -> mapper.readTree(reply) }
            val failure = assertFailsWith<CaptureValidationException>(reply) { ChainCaptureReads.blockTime(rpc, SLOT, budget) }
            assertEquals("BLOCK_TIME_RESPONSE", failure.category, reply)
        }
        listOf("{}", "null", "[1.5]", "[${SLOT + 1}]", "[${SLOT - ChainCaptureFormat.BLOCK_TIME_LOOKBACK_SLOTS - 1}]").forEach { reply ->
            val rpc = SolanaReadRpc { method, _, _ ->
                if (method == ChainCaptureFormat.GET_BLOCK_TIME) throw CaptureProviderException(CaptureCategory.SLOT_SKIPPED)
                mapper.readTree(reply)
            }
            val failure = assertFailsWith<CaptureValidationException>(reply) { ChainCaptureReads.blockTime(rpc, SLOT, budget) }
            assertEquals("BLOCKS_RESPONSE", failure.category, reply)
        }
    }

    @Test
    fun `both block time requests are counted under the BLOCK_TIME purpose`() {
        assertEquals(ApiPurpose.BLOCK_TIME, ApiPurpose.of(ApiProvider.SOLANA_RPC, "getBlockTime"))
        assertEquals(ApiPurpose.BLOCK_TIME, ApiPurpose.of(ApiProvider.SOLANA_RPC, "getBlocks"))
    }

    /** A chain whose [produced] slots have the given unix times; every other slot was skipped, and [withoutTime] slots have none. */
    private inner class Chain(private val produced: Map<Long, Long>, private val withoutTime: Set<Long> = emptySet()) {
        val calls = mutableListOf<Pair<String, List<Any>>>()

        val rpc = SolanaReadRpc { method, params, _ ->
            calls += method to params
            when (method) {
                ChainCaptureFormat.GET_BLOCK_TIME -> {
                    val slot = params[0] as Long
                    when {
                        slot in withoutTime -> mapper.nodeFactory.nullNode()
                        slot in produced -> mapper.readTree(produced.getValue(slot).toString())
                        else -> throw CaptureProviderException(CaptureCategory.SLOT_SKIPPED)
                    }
                }
                ChainCaptureFormat.GET_BLOCKS -> {
                    val range = (params[0] as Long)..(params[1] as Long)
                    mapper.readTree(produced.keys.filter { it in range }.sorted().joinToString(",", "[", "]"))
                }
                else -> error("Unexpected method $method")
            }
        }
    }

    private companion object {
        const val SLOT = 459_495_243L
        const val TIME = 1_792_000_000L
    }
}
