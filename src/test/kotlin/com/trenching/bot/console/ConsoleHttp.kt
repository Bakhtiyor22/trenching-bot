package com.trenching.bot.console

import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class ConsoleReply(val status: Int, val body: JsonNode, val headers: HttpHeaders, val raw: String)

/** Real HTTP against the running server: MockMvc would bypass the servlet container's multipart size limit. */
class ConsoleHttp(port: Int, private val objectMapper: ObjectMapper) {
    private val client = RestClient.create("http://localhost:$port")

    fun get(path: String): ConsoleReply = exchange(client.get().uri(path))

    fun post(path: String, json: String? = null): ConsoleReply {
        val request = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
        json?.let { request.body(it) }
        return exchange(request)
    }

    fun upload(content: ByteArray, fileName: String, name: String? = null): ConsoleReply {
        val parts = LinkedMultiValueMap<String, Any>()
        parts.add("file", object : ByteArrayResource(content) {
            override fun getFilename(): String = fileName
        })
        name?.let { parts.add("name", it) }
        return exchange(client.post().uri("/api/datasets").contentType(MediaType.MULTIPART_FORM_DATA).body(parts))
    }

    private fun exchange(request: RestClient.RequestHeadersSpec<*>): ConsoleReply =
        request.exchange { _, response ->
            val raw = response.bodyTo(String::class.java) ?: ""
            ConsoleReply(response.statusCode.value(), objectMapper.readTree(raw.ifEmpty { "{}" }), response.headers, raw)
        }
}
