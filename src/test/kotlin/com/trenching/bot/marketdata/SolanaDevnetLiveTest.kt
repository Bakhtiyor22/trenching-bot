package com.trenching.bot.marketdata

import java.net.http.HttpClient
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@Tag("live")
class SolanaDevnetLiveTest {
    @Test
    fun `public devnet responds with a node version and confirmed slot`() {
        val requestFactory = JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build())
        requestFactory.setReadTimeout(Duration.ofSeconds(15))
        val client = RestClient.builder().requestFactory(requestFactory)
            .baseUrl("https://api.devnet.solana.com").build()
        val mapper = JsonMapper.builder().build()
        val version = request(client, mapper, "getVersion", emptyList<Any>())
        assertTrue(version.path("solana-core").asString().isNotBlank())
        val slot = request(client, mapper, "getSlot", listOf(mapOf("commitment" to "confirmed")))
        assertTrue(slot.isIntegralNumber && slot.asLong() > 0)
        println("Public Solana devnet verified: nodeVersion=" + version.path("solana-core").asString() + " confirmedSlot=" + slot.asLong())
    }

    private fun request(client: RestClient, mapper: JsonMapper, method: String, params: List<Any>): JsonNode {
        val json = mapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))
        val body = client.post().contentType(MediaType.APPLICATION_JSON).body(json).retrieve().body(String::class.java)
        val response = mapper.readTree(requireNotNull(body))
        assertEquals("2.0", response.path("jsonrpc").asString())
        assertEquals(1, response.path("id").asInt())
        assertTrue(response.path("error").isMissingNode, "RPC error returned")
        assertTrue(!response.path("result").isMissingNode && !response.path("result").isNull)
        return response.path("result")
    }
}
