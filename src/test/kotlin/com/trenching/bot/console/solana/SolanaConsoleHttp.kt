package com.trenching.bot.console.solana

import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class SolanaConsoleHttp(port: Int, private val objectMapper: ObjectMapper) {
    private val client = RestClient.create("http://localhost:$port")

    fun get(path: String): Pair<Int, JsonNode> = parse(raw(client.get().uri(path)))

    fun post(path: String, json: String? = null): Pair<Int, JsonNode> {
        val request = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
        json?.let { request.body(it) }
        return parse(raw(request))
    }

    fun raw(request: RestClient.RequestHeadersSpec<*>): Pair<Int, String> =
        request.exchange { _, response -> response.statusCode.value() to (response.bodyTo(String::class.java) ?: "") }

    private fun parse(response: Pair<Int, String>): Pair<Int, JsonNode> =
        response.first to objectMapper.readTree(response.second.ifEmpty { "{}" })
}
