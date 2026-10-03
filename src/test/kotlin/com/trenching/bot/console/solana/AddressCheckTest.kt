package com.trenching.bot.console.solana

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.solana.SolanaRpcTestData.RPC_URL
import com.trenching.bot.console.solana.SolanaRpcTestData.TOKEN_MINT
import com.trenching.bot.console.solana.SolanaRpcTestData.TRACKED_WALLET
import com.trenching.bot.console.solana.SolanaRpcTestData.expectCall
import com.trenching.bot.console.solana.SolanaRpcTestData.mapper
import com.trenching.bot.console.solana.SolanaRpcTestData.result
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class AddressCheckTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val probe = JsonRpcSolanaProbe(SolanaRpcEndpoint.from(RPC_URL), builder.build(), mapper)
    private val address = WalletAddress.of(TRACKED_WALLET)

    @Test
    fun `asks for the five newest confirmed signatures and the newest successful jsonParsed version 1 transaction`() {
        server.expectCall("getSignaturesForAddress")
            .andExpect(jsonPath("$.params[0]").value(TRACKED_WALLET))
            .andExpect(jsonPath("$.params[1].limit").value(5))
            .andExpect(jsonPath("$.params[1].commitment").value("confirmed"))
            .andRespond(json(signatures(failed("sig-newest-failed"), succeeded("sig-success"), succeeded("sig-older"))))
        server.expectCall("getTransaction")
            .andExpect(jsonPath("$.params[0]").value("sig-success"))
            .andExpect(jsonPath("$.params[1].encoding").value("jsonParsed"))
            .andExpect(jsonPath("$.params[1].maxSupportedTransactionVersion").value(1))
            .andExpect(jsonPath("$.params[1].commitment").value("confirmed"))
            .andRespond(json(transaction(version = "1", postTokenBalances = TOKEN_BALANCES)))

        val outcome = probe.checkAddress(address)

        server.verify()
        val checked = assertIs<AddressCheckOutcome.Checked>(outcome)
        assertEquals("sig-success", checked.signature)
        assertEquals(412_000_100L, checked.slot)
        assertEquals("1", checked.transactionVersion)
        assertEquals(NormalizerField.entries, checked.presentFields)
        assertEquals(emptyList(), checked.missingFields)
    }

    @Test
    fun `reports missing postTokenBalances as missing normalizer fields`() {
        server.expectCall("getSignaturesForAddress").andRespond(json(signatures(succeeded("sig-success"))))
        server.expectCall("getTransaction").andRespond(json(transaction(version = "\"legacy\"", postTokenBalances = null)))

        val checked = assertIs<AddressCheckOutcome.Checked>(probe.checkAddress(address))

        assertEquals(
            listOf(
                NormalizerField.POST_TOKEN_BALANCE_MINT,
                NormalizerField.POST_TOKEN_BALANCE_OWNER,
                NormalizerField.POST_TOKEN_BALANCE_AMOUNT,
                NormalizerField.POST_TOKEN_BALANCE_DECIMALS,
            ),
            checked.missingFields,
        )
        assertEquals("legacy", checked.transactionVersion)
    }

    @Test
    fun `reports a missing block time and unflagged signers`() {
        server.expectCall("getSignaturesForAddress").andRespond(json(signatures(succeeded("sig-success"))))
        server.expectCall("getTransaction").andRespond(json(transaction(
            version = "0", postTokenBalances = TOKEN_BALANCES, blockTime = "null",
            accountKeys = """["$TRACKED_WALLET"]""")))

        val checked = assertIs<AddressCheckOutcome.Checked>(probe.checkAddress(address))

        assertEquals(listOf(NormalizerField.BLOCK_TIME, NormalizerField.SIGNER_FLAGS), checked.missingFields)
    }

    @Test
    fun `five failed signatures give no successful transaction without fetching one`() {
        server.expectCall("getSignaturesForAddress")
            .andRespond(json(signatures(*(1..5).map { failed("sig-failed-$it") }.toTypedArray())))

        val outcome = probe.checkAddress(address)

        server.verify()
        assertEquals(AddressCheckOutcome.NoSuccessfulTransaction(signaturesExamined = 5), outcome)
    }

    @Test
    fun `a rate limited signature lookup is a failed check`() {
        server.expectCall("getSignaturesForAddress").andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

        val outcome = probe.checkAddress(address)

        server.verify()
        assertEquals(AddressCheckOutcome.Failed(ProbeOutcome.RateLimited), outcome)
    }

    @Test
    fun `an unconfigured probe makes no call for an address check`() {
        val unconfigured = JsonRpcSolanaProbe(null, builder.build(), mapper)

        val outcome = unconfigured.checkAddress(address)

        server.verify()
        assertEquals(AddressCheckOutcome.Failed(ProbeOutcome.NotConfigured), outcome)
    }

    private fun json(body: String) = withSuccess(body, MediaType.APPLICATION_JSON)

    private fun signatures(vararg entries: String) = result(entries.joinToString(",", "[", "]"))

    private fun succeeded(signature: String) =
        """{"signature":"$signature","slot":412000100,"err":null,"memo":null,"blockTime":1790000000,"confirmationStatus":"confirmed"}"""

    private fun failed(signature: String) =
        """{"signature":"$signature","slot":412000200,"err":{"InstructionError":[2,{"Custom":6001}]},"memo":null,"blockTime":1790000100,"confirmationStatus":"confirmed"}"""

    private fun transaction(
        version: String,
        postTokenBalances: String?,
        blockTime: String = "1790000000",
        accountKeys: String = """[{"pubkey":"$TRACKED_WALLET","signer":true,"writable":true,"source":"transaction"},""" +
            """{"pubkey":"$TOKEN_MINT","signer":false,"writable":false,"source":"transaction"}]""",
    ): String {
        val balances = postTokenBalances?.let { ""","postTokenBalances":$it""" } ?: ""
        return result(
            """{"slot":412000100,"blockTime":$blockTime,"version":$version,""" +
                """"meta":{"err":null,"fee":5000,"preTokenBalances":[]$balances},""" +
                """"transaction":{"signatures":["sig-success"],"message":{"accountKeys":$accountKeys,"instructions":[]}}}""",
        )
    }

    private companion object {
        const val TOKEN_BALANCES =
            """[{"accountIndex":2,"mint":"$TOKEN_MINT","owner":"$TRACKED_WALLET","programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",""" +
                """"uiTokenAmount":{"amount":"18446744073709551615","decimals":6,"uiAmount":null,"uiAmountString":"18446744073709.551615"}}]"""
    }
}
