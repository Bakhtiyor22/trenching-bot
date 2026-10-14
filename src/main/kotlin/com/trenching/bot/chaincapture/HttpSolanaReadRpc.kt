package com.trenching.bot.chaincapture

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.DeserializationFeature

class HttpSolanaReadRpc(
    private val settings: SolanaRpcSettings,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val timing: CaptureTiming = SystemCaptureTiming,
) : SolanaReadRpc {
    private val log = LoggerFactory.getLogger(HttpSolanaReadRpc::class.java)
    private val endpoint = endpoint(settings.endpoint)
    private val credentialFragments = endpoint?.let(::credentialFragments).orEmpty()
    private val client = RestClient.builder().requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(settings.connectTimeout).build()).apply {
        setReadTimeout(settings.readTimeout)
    }).build()
    private var nextRequestNanos = 0L

    @Synchronized
    override fun call(method: String, params: List<Any>, budget: CaptureBudget): JsonNode {
        if (method !in ChainCaptureFormat.READ_METHODS) throw CaptureValidationException("RPC_METHOD")
        val uri = endpoint ?: throw CaptureProviderException("NOT_CONFIGURED")
        budget.startDeadline(timing.nanoTime(), budget.operationTimeout ?: settings.operationTimeout)
        var last = CaptureHttpReply.Failed("UNAVAILABLE", true)
        for (attempt in 1..MAX_ATTEMPTS) {
            val waitNanos = maxOf(0, nextRequestNanos - timing.nanoTime())
            pause(Duration.ofNanos(waitNanos), budget)
            budget.checkDeadline(timing.nanoTime())
            budget.request()
            nextRequestNanos = timing.nanoTime() + RATE_INTERVAL.toNanos()
            val reply = try { request(uri, method, params, budget) } catch (_: RestClientException) {
                CaptureHttpReply.Failed("IO", true)
            } catch (_: IOException) { CaptureHttpReply.Failed("IO", true) }
            when (reply) {
                is CaptureHttpReply.Success -> return reply.result
                is CaptureHttpReply.Failed -> {
                    last = reply
                    if (!reply.retryable || attempt == MAX_ATTEMPTS) break
                    val backoff = Duration.ofMillis((BASE_BACKOFF_MILLIS shl (attempt - 1)) + timing.jitterMillis(attempt))
                    val delay = maxOf(backoff, reply.retryAfter ?: Duration.ZERO)
                    log.warn("chain_capture rpc_retry operation={} category={} attempt={}", method, reply.category, attempt)
                    pause(delay, budget)
                }
            }
        }
        throw CaptureProviderException(last.category, last.retryAfter, budget.requestsUsed, budget.bytesUsed)
    }

    private fun request(uri: URI, method: String, params: List<Any>, budget: CaptureBudget): CaptureHttpReply =
        client.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params)))
            .exchange { _, response ->
                val status = response.statusCode.value()
                val bytes = readBounded(response.body, budget)
                when {
                    status == 401 || status == 403 -> CaptureHttpReply.Failed("AUTHENTICATION", false)
                    status == 429 -> CaptureHttpReply.Failed("RATE_LIMITED", true, retryAfter(response.headers.getFirst("Retry-After")))
                    status in 500..599 -> CaptureHttpReply.Failed("UNAVAILABLE", true)
                    status !in 200..299 -> CaptureHttpReply.Failed("HTTP", false)
                    else -> parse(bytes)
                }
            }

    private fun readBounded(input: java.io.InputStream, budget: CaptureBudget): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            budget.checkDeadline(timing.nanoTime())
            val readLimit = minOf(buffer.size.toLong(), budget.remainingBytes() + 1, ChainCaptureFormat.MAX_RESPONSE_BYTES - output.size() + 1).toInt()
            val count = input.read(buffer, 0, maxOf(1, readLimit))
            if (count < 0) break
            budget.bytes(count.toLong())
            if (output.size().toLong() + count > ChainCaptureFormat.MAX_RESPONSE_BYTES) throw CaptureBudgetException("RESPONSE_BYTES", budget.requestsUsed, budget.bytesUsed)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun parse(bytes: ByteArray): CaptureHttpReply {
        val node = try { mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(bytes) } catch (_: JacksonException) { return CaptureHttpReply.Failed("MALFORMED", false) }
        val version = node.path("jsonrpc")
        val id = node.path("id")
        if (!node.isObject || !version.isString || version.asString() != "2.0" || !id.isIntegralNumber || !id.canConvertToLong() || id.asLong() != 1L) {
            return CaptureHttpReply.Failed("MALFORMED", false)
        }
        if (node.hasNonNull("error")) {
            val code = node.path("error").path("code")
            return when (if (code.isIntegralNumber && code.canConvertToInt()) code.asInt() else null) {
                -32005 -> CaptureHttpReply.Failed("UNAVAILABLE", true)
                -32015 -> CaptureHttpReply.Failed("UNSUPPORTED_VERSION", false)
                else -> CaptureHttpReply.Failed("RPC_ERROR", false)
            }
        }
        if (!node.has("result")) return CaptureHttpReply.Failed("MALFORMED", false)
        val result = node.path("result")
        if (echoesCredential(result)) return CaptureHttpReply.Failed("UNSAFE_RESULT", false)
        return CaptureHttpReply.Success(result)
    }

    private fun echoesCredential(node: JsonNode): Boolean = when {
        node.isString -> containsCredential(node.asString())
        node.isObject -> node.properties().any { containsCredential(it.key) || echoesCredential(it.value) }
        node.isArray -> node.any(::echoesCredential)
        else -> false
    }

    private fun containsCredential(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        return credentialFragments.any(normalized::contains)
    }

    private fun credentialFragments(uri: URI): List<String> {
        val raw = listOf(uri.toString()) + uri.rawPath.orEmpty().split('/') + uri.rawQuery.orEmpty().split('&').map { it.substringAfter('=') }
        return raw.flatMap { listOf(it, URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8)) }
            .filter { it.length >= MIN_CREDENTIAL_FRAGMENT_LENGTH }
            .map { it.lowercase(Locale.ROOT) }
            .distinct()
    }

    private fun pause(duration: Duration, budget: CaptureBudget) {
        if (duration.isZero) return
        if (duration.toNanos() >= budget.remainingNanos(timing.nanoTime())) throw CaptureBudgetException("DEADLINE", budget.requestsUsed, budget.bytesUsed)
        try { timing.pause(duration) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CaptureProviderException("INTERRUPTED")
        }
    }

    private fun retryAfter(value: String?): Duration? {
        if (value == null) return null
        val seconds = value.toLongOrNull()
        if (seconds != null) return if (seconds in 0..1800) Duration.ofSeconds(seconds) else Duration.ofMinutes(30)
        return try { maxOf(Duration.ZERO, Duration.between(clock.instant(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())) }
        catch (_: DateTimeParseException) { null }
    }

    private fun endpoint(value: String?): URI? {
        if (value.isNullOrBlank()) return null
        val uri = try { URI(value) } catch (_: URISyntaxException) { throw CaptureValidationException("ENDPOINT") }
        if (uri.scheme !in setOf("http", "https") || uri.host == null || uri.userInfo != null || uri.fragment != null) throw CaptureValidationException("ENDPOINT")
        if (uri.scheme == "http" && uri.host !in setOf("127.0.0.1", "localhost", "::1", "[::1]")) throw CaptureValidationException("ENDPOINT_TLS")
        return uri
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BASE_BACKOFF_MILLIS = 500L
        val RATE_INTERVAL: Duration = Duration.ofMillis(500)
        const val MIN_CREDENTIAL_FRAGMENT_LENGTH = 8
    }
}
