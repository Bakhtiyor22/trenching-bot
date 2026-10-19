package com.trenching.bot.chaincapture

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class ConfirmedTipReadTest {
    private val mapper = JsonMapper.builder().build()
    private val budget = CaptureBudget(10, 1_000_000)

    @Test
    fun `reads the tip slot at confirmed commitment through an allowed read method`() {
        var method: String? = null
        var params: List<Any>? = null
        val rpc = SolanaReadRpc { calledMethod, calledParams, _ ->
            method = calledMethod
            params = calledParams
            mapper.readTree("458870632")
        }

        assertEquals(458_870_632L, ChainCaptureReads.confirmedTip(rpc, budget))
        assertEquals("getSlot", method)
        assertTrue("getSlot" in ChainCaptureFormat.READ_METHODS)
        assertEquals(listOf<Any>(mapOf("commitment" to "confirmed")), params)
    }

    @Test
    fun `a reply that is not a non-negative whole slot is refused`() {
        listOf("\"458870632\"", "-1", "1.5", "null", "{}", "123456789012345678901234567890").forEach { reply ->
            val rpc = SolanaReadRpc { _, _, _ -> mapper.readTree(reply) }
            val failure = assertFailsWith<CaptureValidationException>(reply) { ChainCaptureReads.confirmedTip(rpc, budget) }
            assertEquals("SLOT_RESPONSE", failure.category)
        }
    }
}
