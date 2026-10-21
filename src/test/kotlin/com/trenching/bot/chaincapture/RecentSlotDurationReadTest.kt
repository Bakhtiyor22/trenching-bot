package com.trenching.bot.chaincapture

import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class RecentSlotDurationReadTest {
    private val mapper = JsonMapper.builder().build()
    private val budget = CaptureBudget(10, 1_000_000)

    @Test
    fun `averages the recent performance samples into a duration per slot`() {
        var method: String? = null
        var params: List<Any>? = null
        val rpc = SolanaReadRpc { calledMethod, calledParams, _ ->
            method = calledMethod
            params = calledParams
            mapper.readTree("""[{"slot":3,"numSlots":276,"numTransactions":1,"samplePeriodSecs":60},
                {"slot":2,"numSlots":277,"numTransactions":1,"samplePeriodSecs":60}]""")
        }

        assertEquals(Duration.ofNanos(216_998_191), ChainCaptureReads.recentSlotDuration(rpc, budget))
        assertEquals("getRecentPerformanceSamples", method)
        assertTrue("getRecentPerformanceSamples" in ChainCaptureFormat.READ_METHODS)
        assertEquals(listOf<Any>(ChainCaptureFormat.PERFORMANCE_SAMPLES), params)
    }

    @Test
    fun `no sample with slots gives no duration`() {
        listOf("[]", """[{"slot":1,"numSlots":0,"samplePeriodSecs":60}]""").forEach { reply ->
            assertNull(ChainCaptureReads.recentSlotDuration(SolanaReadRpc { _, _, _ -> mapper.readTree(reply) }, budget), reply)
        }
    }

    @Test
    fun `a reply that is not a list of whole non-negative counts is refused`() {
        listOf("{}", "null", """[{"numSlots":-1,"samplePeriodSecs":60}]""", """[{"numSlots":10}]""",
            """[{"numSlots":1.5,"samplePeriodSecs":60}]""").forEach { reply ->
            val rpc = SolanaReadRpc { _, _, _ -> mapper.readTree(reply) }
            val failure = assertFailsWith<CaptureValidationException>(reply) { ChainCaptureReads.recentSlotDuration(rpc, budget) }
            assertEquals("PERFORMANCE_RESPONSE", failure.category)
        }
    }
}
