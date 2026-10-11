package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.chaincapture.ChainCaptureReads
import com.trenching.bot.chaincapture.SolanaReadRpc
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class MintOwnerReadTest {
    private val mapper = JsonMapper.builder().build()
    private val budget = CaptureBudget(10, 1_000_000)

    @Test
    fun `reads only the mint account's owner with a zero length data slice at confirmed commitment`() {
        var method: String? = null
        var params: List<Any>? = null
        val rpc = SolanaReadRpc { calledMethod, calledParams, _ ->
            method = calledMethod
            params = calledParams
            mapper.readTree(account(""""owner":"$TOKEN_2022""""))
        }

        assertEquals(TOKEN_2022, ChainCaptureReads.mintOwner(rpc, MINT, budget))
        assertEquals("getAccountInfo", method)
        assertTrue("getAccountInfo" in ChainCaptureFormat.READ_METHODS)
        assertEquals(listOf<Any>(MINT, mapOf("encoding" to "base64", "commitment" to "confirmed",
            "dataSlice" to mapOf("offset" to 0, "length" to 0))), params)
    }

    @Test
    fun `a missing mint account is refused as not found`() {
        val rpc = SolanaReadRpc { _, _, _ -> mapper.readTree("""{"context":{"slot":1},"value":null}""") }
        val failure = assertFailsWith<CaptureValidationException> { ChainCaptureReads.mintOwner(rpc, MINT, budget) }
        assertEquals("MINT_NOT_FOUND", failure.category)
    }

    @Test
    fun `a malformed account reply is refused`() {
        mapOf(
            "\"text\"" to "ACCOUNT_INFO",
            """{"context":{"slot":1}}""" to "ACCOUNT_INFO",
            account(""""owner":7""") to "ACCOUNT_INFO",
            account(""""space":0""") to "ACCOUNT_INFO",
            account(""""owner":"not-a-key"""") to "ACCOUNT_OWNER_ENCODING",
        ).forEach { (reply, category) ->
            val rpc = SolanaReadRpc { _, _, _ -> mapper.readTree(reply) }
            val failure = assertFailsWith<CaptureValidationException>(reply) { ChainCaptureReads.mintOwner(rpc, MINT, budget) }
            assertEquals(category, failure.category, reply)
        }
    }

    private fun account(fields: String): String =
        """{"context":{"slot":1},"value":{"data":["","base64"],"executable":false,"lamports":1461600,$fields}}"""

    private companion object {
        const val MINT = "hoSDHBQR236CoSLptRpDKPSU1JbcG2Ry52X1iq6pump"
        const val TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    }
}
