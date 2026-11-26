package com.trenching.bot.chaincapture

import com.trenching.bot.apiusage.ApiCall
import com.trenching.bot.apiusage.ApiCallOutcome
import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.common.ExceptionCauses
import com.trenching.bot.common.LogLine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
 * rate limited, unavailable or transport failure is sent again until [maxAttempts] requests were made for one call. [endpoints]
 * is asked before every attempt. A 429 saying the account's credits are used up reports it exhausted and moves the call to
 * another account without using an attempt, once per account; it never counts as a rate limit.
 */
class HttpSolanaReadRpc(
    private val settings: SolanaRpcSettings,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val timing: CaptureTiming = SystemCaptureTiming,
    private val limiter: ProviderRequestLimiter = ProviderRequestLimiter.of(settings, timing),
    private val recorder: ApiUsageRecorder = ApiUsageRecorder.NONE,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val endpoints: RpcEndpointSource = RpcEndpointSource.fixed(settings.endpoint),
    private val limiters: RpcLimiterRegistry = RpcLimiterRegistry(timing),
) : SolanaReadRpc {
    init {
        require(maxAttempts in 1..DEFAULT_MAX_ATTEMPTS) { "maxAttempts must be between 1 and $DEFAULT_MAX_ATTEMPTS, was $maxAttempts" }
        endpoint(settings.endpoint)
    }

    private val log = LoggerFactory.getLogger(HttpSolanaReadRpc::class.java)
    private val resolvedLimiters = ConcurrentHashMap<String, ProviderRequestLimiter>()
    private val client = RestClient.builder().requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(settings.connectTimeout).build()).apply {
        setReadTimeout(settings.readTimeout)
    }).build()

    override fun call(method: String, params: List<Any>, budget: CaptureBudget): JsonNode {
        if (method !in ChainCaptureFormat.READ_METHODS) throw CaptureValidationException("RPC_METHOD")
        var choice = endpoints.choose()
        if (choice is RpcEndpointChoice.NotConfigured) throw CaptureProviderException(CaptureCategory.NOT_CONFIGURED)
        budget.startDeadline(timing.nanoTime(), budget.operationTimeout ?: settings.operationTimeout)
        var last = CaptureHttpReply.Failed("UNAVAILABLE", true)
        var account: String? = null
        val movedFrom = mutableSetOf<String>()
        var attempt = 1
        var requestsSent = 0
        while (true) {
            val target = when (val offered = choice) {
                is RpcEndpointChoice.Use -> offered.target
                RpcEndpointChoice.NotConfigured -> throw CaptureProviderException(CaptureCategory.NOT_CONFIGURED)
                RpcEndpointChoice.OutOfCredits -> { last = CaptureHttpReply.Failed(CaptureCategory.CREDITS_EXHAUSTED, false); break }
            }
            account = target.account
            val uri = endpoint(target.endpoint) ?: throw CaptureProviderException(CaptureCategory.NOT_CONFIGURED)
            val accountLimiter = limiterFor(target.endpoint)
            requestsSent++
            when (val reply = accountLimiter.withStart(budget) { send(uri, method, params, budget) }) {
                is CaptureHttpReply.Success -> return reply.result
                is CaptureHttpReply.Failed -> last = reply
            }
            if (last.category == CaptureCategory.CREDITS_EXHAUSTED) {
                endpoints.exhausted(target.account)
                movedFrom += target.account
                LogLine("RPC account is out of credits.").field("operation", method).field("account", target.account).debug(log)
                choice = endpoints.choose()
                val next = choice
                if (next is RpcEndpointChoice.Use && next.target.account in movedFrom) break
                continue
            }
            if (last.rateLimited) budget.counters.rateLimited(last.retryAfter)
            if (!last.retryable || attempt == maxAttempts) break
            budget.counters.retry()
            val backoff = Duration.ofMillis((BASE_BACKOFF_MILLIS shl (attempt - 1)) + timing.jitterMillis(attempt))
            val delay = maxOf(backoff, last.retryAfter ?: Duration.ZERO)
            LogLine("RPC call will be retried.").field("operation", method).field("category", last.category).field("cause", last.cause)
                .field("account", target.account).field("attempt", "$attempt/$maxAttempts").debug(log)
            if (last.rateLimited) accountLimiter.holdStarts(delay, budget) else pause(delay, budget)
            attempt++
            choice = endpoints.choose()
        }
        if (requestsSent == 0) {
            LogLine("RPC call not sent: {}.", ProviderFailureMeanings.meaning(last.category)).field("operation", method).field("category", last.category).debug(log)
            throw CaptureProviderException(last.category, last.retryAfter, budget.requestsUsed, budget.bytesUsed)
        }
        val failed = LogLine("RPC call failed after {}: {}.", if (attempt == 1) "1 attempt" else "$attempt attempts", ProviderFailureMeanings.meaning(last.category))
            .field("operation", method).field("category", last.category).field("cause", last.cause).field("account", account).field("attempts", attempt)
        if (last.category in CaptureCategory.ANSWERS) failed.debug(log) else failed.warn(log)
        throw CaptureProviderException(last.category, last.retryAfter, budget.requestsUsed, budget.bytesUsed)
    }

    /** The settings' own endpoint keeps the limiter it was built with; any other account's endpoint gets the limiter of its own, looked up once. */
    private fun limiterFor(endpoint: String): ProviderRequestLimiter =
        if (endpoint == settings.endpoint) limiter else resolvedLimiters.computeIfAbsent(endpoint) { limiters.limiterFor(it, settings) }

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
                    status == 429 && reportsCreditsUsedUp(bytes) -> CaptureHttpReply.Failed(CaptureCategory.CREDITS_EXHAUSTED, false)
                    status == 429 -> CaptureHttpReply.Failed(CaptureCategory.RATE_LIMITED, true, retryAfter(response.headers.getFirst("Retry-After")), rateLimited = true)
                    status in 500..599 -> CaptureHttpReply.Failed("UNAVAILABLE", true)
                    status !in 200..299 -> CaptureHttpReply.Failed("HTTP", false)
                    else -> parse(bytes, uri)
                }
            }

    private fun reportsCreditsUsedUp(body: ByteArray): Boolean =
        String(body, StandardCharsets.UTF_8).contains(CREDITS_USED_UP_TEXT, ignoreCase = true)

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

    private fun parse(bytes: ByteArray, uri: URI): CaptureHttpReply {
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
                -32007, -32009 -> CaptureHttpReply.Failed(CaptureCategory.SLOT_SKIPPED, false)
                else -> CaptureHttpReply.Failed("RPC_ERROR", false)
            }
        }
        if (!node.has("result")) return CaptureHttpReply.Failed("MALFORMED", false)
        val result = node.path("result")
        if (echoesCredential(result, credentialFragments(uri))) return CaptureHttpReply.Failed("UNSAFE_RESULT", false)
        return CaptureHttpReply.Success(result)
    }

    private fun echoesCredential(node: JsonNode, fragments: List<String>): Boolean = when {
        node.isString -> containsCredential(node.asString(), fragments)
        node.isObject -> node.properties().any { containsCredential(it.key, fragments) || echoesCredential(it.value, fragments) }
        node.isArray -> node.any { echoesCredential(it, fragments) }
        else -> false
    }

    private fun containsCredential(text: String, fragments: List<String>): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        return fragments.any(normalized::contains)
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

    private fun endpoint(value: String?): URI? = if (value.isNullOrBlank()) null else RpcEndpointRules.uri(value)

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        private val MAX_RETRY_AFTER: Duration = Duration.ofMinutes(30)
        private const val BASE_BACKOFF_MILLIS = 500L
        private const val CREDITS_USED_UP_TEXT = "max usage reached"
        private const val MIN_CREDENTIAL_FRAGMENT_LENGTH = 8
    }
}
