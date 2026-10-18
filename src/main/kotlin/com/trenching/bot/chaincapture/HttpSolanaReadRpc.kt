package com.trenching.bot.chaincapture

import com.trenching.bot.apiusage.ApiCall
import com.trenching.bot.apiusage.ApiCallOutcome
import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.common.ExceptionCauses
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
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.DeserializationFeature

/**
 * Every HTTP request it sends, each retry included, is recorded once in [recorder] under the budget's usage context. A
 * rate limited, unavailable or transport failure is sent again until [maxAttempts] requests were made for one call.
 */
class HttpSolanaReadRpc(
    private val settings: SolanaRpcSettings,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val timing: CaptureTiming = SystemCaptureTiming,
    private val limiter: ProviderRequestLimiter = ProviderRequestLimiter.of(settings, timing),
    private val recorder: ApiUsageRecorder = ApiUsageRecorder.NONE,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) : SolanaReadRpc {
    init {
        require(maxAttempts in 1..DEFAULT_MAX_ATTEMPTS) { "maxAttempts must be between 1 and $DEFAULT_MAX_ATTEMPTS, was $maxAttempts" }
    }

    private val log = LoggerFactory.getLogger(HttpSolanaReadRpc::class.java)
    private val endpoint = endpoint(settings.endpoint)
    private val credentialFragments = endpoint?.let(::credentialFragments).orEmpty()
    private val client = RestClient.builder().requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(settings.connectTimeout).build()).apply {
        setReadTimeout(settings.readTimeout)
    }).build()

    override fun call(method: String, params: List<Any>, budget: CaptureBudget): JsonNode {
        if (method !in ChainCaptureFormat.READ_METHODS) throw CaptureValidationException("RPC_METHOD")
        val uri = endpoint ?: throw CaptureProviderException("NOT_CONFIGURED")
        budget.startDeadline(timing.nanoTime(), budget.operationTimeout ?: settings.operationTimeout)
        var last = CaptureHttpReply.Failed("UNAVAILABLE", true)
        var attempts = 0
        for (attempt in 1..maxAttempts) {
            attempts = attempt
            when (val reply = limiter.withStart(budget) { send(uri, method, params, budget) }) {
                is CaptureHttpReply.Success -> return reply.result
                is CaptureHttpReply.Failed -> {
                    last = reply
                    if (reply.rateLimited) budget.counters.rateLimited(reply.retryAfter)
                    if (!reply.retryable || attempt == maxAttempts) break
                    budget.counters.retry()
                    val backoff = Duration.ofMillis((BASE_BACKOFF_MILLIS shl (attempt - 1)) + timing.jitterMillis(attempt))
                    val delay = maxOf(backoff, reply.retryAfter ?: Duration.ZERO)
                    log.warn("chain_capture rpc_retry operation={} category={} cause={} attempt={}", method, reply.category, reply.cause, attempt)
                    if (reply.rateLimited) limiter.holdStarts(delay, budget) else pause(delay, budget)
                }
            }
        }
        log.warn("chain_capture rpc_failed operation={} category={} cause={} attempts={}", method, last.category, last.cause, attempts)
        throw CaptureProviderException(last.category, last.retryAfter, budget.requestsUsed, budget.bytesUsed)
    }

    private fun send(uri: URI, method: String, params: List<Any>, budget: CaptureBudget): CaptureHttpReply {
        budget.checkDeadline(timing.nanoTime())
        budget.request()
        val received = AtomicLong()
        val reply = try {
            request(uri, method, params, budget, received)
        } catch (exception: RestClientException) {
            transportFailure(exception)
        } catch (exception: IOException) {
            transportFailure(exception)
        } catch (exception: CaptureException) {
            record(method, budget, ApiCallOutcome.FAILED, received.get())
            throw exception
        }
        record(method, budget, outcome(reply), received.get())
        return reply
    }

    private fun record(method: String, budget: CaptureBudget, outcome: ApiCallOutcome, responseBytes: Long) =
        recorder.record(ApiCall(ApiProvider.SOLANA_RPC, method, budget.usage, outcome, responseBytes))

    /** Only an HTTP 429 counts as rate limited; a JSON-RPC rate-limit error is a failed reply, like every other failure. */
    private fun outcome(reply: CaptureHttpReply): ApiCallOutcome = when (reply) {
        is CaptureHttpReply.Success -> ApiCallOutcome.OK
        is CaptureHttpReply.Failed -> if (reply.category == CaptureCategory.RATE_LIMITED) ApiCallOutcome.RATE_LIMITED else ApiCallOutcome.FAILED
    }

    /** No HTTP response at all: a timeout, refused or reset connection, DNS or TLS failure, told apart by its root cause. */
    private fun transportFailure(exception: Exception) = CaptureHttpReply.Failed("IO", true, cause = ExceptionCauses.rootName(exception))

    private fun request(uri: URI, method: String, params: List<Any>, budget: CaptureBudget, received: AtomicLong): CaptureHttpReply =
        client.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params)))
            .exchange { _, response ->
                val status = response.statusCode.value()
                val bytes = readBounded(response.body, budget, received)
                when {
                    status == 401 || status == 403 -> CaptureHttpReply.Failed("AUTHENTICATION", false)
                    status == 429 -> CaptureHttpReply.Failed(CaptureCategory.RATE_LIMITED, true, retryAfter(response.headers.getFirst("Retry-After")), rateLimited = true)
                    status in 500..599 -> CaptureHttpReply.Failed("UNAVAILABLE", true)
                    status !in 200..299 -> CaptureHttpReply.Failed("HTTP", false)
                    else -> parse(bytes)
                }
            }

    private fun readBounded(input: java.io.InputStream, budget: CaptureBudget, received: AtomicLong): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            budget.checkDeadline(timing.nanoTime())
            val readLimit = minOf(buffer.size.toLong(), budget.remainingBytes() + 1, ChainCaptureFormat.MAX_RESPONSE_BYTES - output.size() + 1).toInt()
            val count = input.read(buffer, 0, maxOf(1, readLimit))
            if (count < 0) break
            received.addAndGet(count.toLong())
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
                -32005 -> CaptureHttpReply.Failed("UNAVAILABLE", true, rateLimited = true)
                -32015 -> CaptureHttpReply.Failed(CaptureCategory.UNSUPPORTED_VERSION, false)
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
        val requested = value.toLongOrNull()?.let { seconds -> Duration.ofSeconds(seconds.coerceIn(0, MAX_RETRY_AFTER.seconds)) }
            ?: try { Duration.between(clock.instant(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()) }
            catch (_: DateTimeParseException) { return null }
        return requested.coerceIn(Duration.ZERO, MAX_RETRY_AFTER)
    }

    private fun endpoint(value: String?): URI? {
        if (value.isNullOrBlank()) return null
        val uri = try { URI(value) } catch (_: URISyntaxException) { throw CaptureValidationException("ENDPOINT") }
        if (uri.scheme !in setOf("http", "https") || uri.host == null || uri.userInfo != null || uri.fragment != null) throw CaptureValidationException("ENDPOINT")
        if (uri.scheme == "http" && uri.host !in setOf("127.0.0.1", "localhost", "::1", "[::1]")) throw CaptureValidationException("ENDPOINT_TLS")
        return uri
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        private val MAX_RETRY_AFTER: Duration = Duration.ofMinutes(30)
        private const val BASE_BACKOFF_MILLIS = 500L
        private const val MIN_CREDENTIAL_FRAGMENT_LENGTH = 8
    }
}
