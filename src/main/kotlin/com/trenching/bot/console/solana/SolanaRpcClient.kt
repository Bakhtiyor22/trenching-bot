package com.trenching.bot.console.solana

import com.trenching.bot.console.solana.ProbeOutcome.Unavailable.Companion.MALFORMED
import com.trenching.bot.console.solana.dto.JsonRpcRequest
import com.trenching.bot.console.solana.dto.JsonRpcResponse
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/**
 * One JSON-RPC POST per call, no retries. Every failure becomes a [ProbeOutcome.Failure] whose text
 * never carries the URL: exception messages are discarded because RestClient quotes the full URL in them.
 */
class SolanaRpcClient(
    private val restClient: RestClient,
    private val endpoint: SolanaRpcEndpoint,
    private val objectMapper: ObjectMapper,
) {
    fun call(method: String, params: List<Any> = emptyList()): RpcReply =
        try {
            restClient.post()
                .uri(endpoint.uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(JsonRpcRequest(method, params)))
                .exchange { _, response ->
                    val status = response.statusCode
                    when {
                        status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS) -> RpcReply.Failed(ProbeOutcome.RateLimited)
                        !status.is2xxSuccessful -> RpcReply.Failed(ProbeOutcome.Unavailable("HTTP ${status.value()}"))
                        else -> parse(response.bodyTo(String::class.java).orEmpty())
                    }
                }
        } catch (e: RestClientException) {
            RpcReply.Failed(classify(e))
        }

    private fun parse(body: String): RpcReply {
        val response = try {
            objectMapper.readValue(body, JsonRpcResponse::class.java)
        } catch (_: JacksonException) {
            return RpcReply.Failed(MALFORMED)
        }
        val error = response.error
        return when {
            error != null -> RpcReply.Failed(ProbeOutcome.RpcError(error.code, sanitize(error.message)))
            else -> RpcReply.Success(response.result?.takeUnless { it.isNull })
        }
    }

    private fun sanitize(message: String): String = endpoint.redact(message).take(MAX_MESSAGE_LENGTH)

    private fun classify(e: RestClientException): ProbeOutcome.Failure {
        val causes = generateSequence<Throwable>(e) { it.cause }.toList()
        return when {
            causes.any { it is HttpTimeoutException || it is SocketTimeoutException || it is TimeoutException } ->
                ProbeOutcome.Timeout
            causes.any { it is UnknownHostException } -> ProbeOutcome.Unavailable("unknown host")
            causes.any { it is ConnectException } -> ProbeOutcome.Unavailable("connection refused")
            causes.any { it is JacksonException } -> MALFORMED
            else -> ProbeOutcome.Unavailable("I/O error")
        }
    }

    private companion object {
        const val MAX_MESSAGE_LENGTH = 300
    }
}
