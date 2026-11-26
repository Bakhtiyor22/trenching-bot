package com.trenching.bot.console.solana.accounts

import ch.qos.logback.classic.Level
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.trenching.bot.apiusage.ApiCall
import com.trenching.bot.apiusage.ApiPurpose
import com.trenching.bot.apiusage.ApiCallOutcome
import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.console.solana.SolanaRestClients
import com.trenching.bot.console.solana.SolanaRpcTestData
import com.trenching.bot.console.solana.accounts.RpcAccountTestData.BACKUP_KEY
import com.trenching.bot.console.solana.accounts.RpcAccountTestData.PRIMARY_KEY
import com.trenching.bot.console.solana.accounts.RpcAccountTestData.PRIMARY_PROJECT
import com.trenching.bot.console.solana.accounts.domain.BalanceProblem
import com.trenching.bot.console.solana.accounts.domain.BalanceRead
import com.trenching.bot.console.solana.accounts.domain.RpcAccountBalance
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfigs
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import com.trenching.bot.console.solana.accounts.infrastructure.helius.HeliusApiKeys
import com.trenching.bot.console.solana.accounts.infrastructure.helius.HeliusUsageClient
import com.trenching.bot.notification.LogCapture
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HeliusUsageClientTest {
    private class Request(val method: String, val path: String, val query: String?, val apiKeyHeader: String?)

    private val requests = CopyOnWriteArrayList<Request>()
    private val calls = ConcurrentLinkedQueue<ApiCall>()
    @Volatile private var status = 200
    @Volatile private var body = USAGE
    @Volatile private var hang = false

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange: HttpExchange ->
            requests += Request(exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestURI.rawQuery, exchange.requestHeaders.getFirst("X-Api-Key"))
            if (hang) Thread.sleep(2_000)
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        executor = Executors.newCachedThreadPool()
        start()
    }
    private val baseUrl = "http://127.0.0.1:${server.address.port}"

    @AfterEach
    fun stop() = server.stop(0)

    private fun client(configs: RpcAccountConfigs = RpcAccountTestData.both(), readTimeout: Duration = Duration.ofSeconds(5), base: String = baseUrl) =
        HeliusUsageClient(configs, base, SolanaRestClients.create(Duration.ofSeconds(5), readTimeout), SolanaRpcTestData.mapper) { calls += it }

    @Test
    fun `reads the documented usage answer into whole credits, with the key only in the header`() {
        val read = client().read(RpcAccountName.PRIMARY)

        assertEquals(
            BalanceRead.Read(RpcAccountBalance(750_000, 250_000, 1_000_000, LocalDate.parse("2026-12-05"), "Free")),
            read,
        )
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("/v0/admin/projects/$PRIMARY_PROJECT/usage", request.path)
        assertNull(request.query)
        assertEquals(PRIMARY_KEY, request.apiKeyHeader)
    }

    @Test
    fun `the backup is read with its own project id and key`() {
        client().read(RpcAccountName.BACKUP)

        assertEquals("/v0/admin/projects/${RpcAccountTestData.BACKUP_PROJECT}/usage", requests.single().path)
        assertEquals(BACKUP_KEY, requests.single().apiKeyHeader)
    }

    @Test
    fun `fractions are floored and no double is involved`() {
        body = usage(remaining = "12345.99", used = "987654321.5", limit = "1000000000.0")

        val balance = assertIs<BalanceRead.Read>(client().read(RpcAccountName.PRIMARY)).balance

        assertEquals(listOf(12_345L, 987_654_321L, 1_000_000_000L), listOf(balance.creditsRemaining, balance.creditsUsed, balance.creditsLimit))
    }

    @Test
    fun `a billing cycle of null and extra fields do not matter, and a missing plan reads as unknown`() {
        body = """{"creditsRemaining":5,"creditsUsed":0,"creditCycle":{"start":"2026-11-05","end":"2026-12-05"},"subscriptionDetails":{"billingCycle":null,"creditsLimit":10},"extra":1}"""

        val balance = assertIs<BalanceRead.Read>(client().read(RpcAccountName.PRIMARY)).balance

        assertEquals("unknown", balance.plan)
    }

    @ParameterizedTest
    @CsvSource("400, INVALID_PROJECT", "401, AUTHENTICATION", "403, ADMIN_API_DISABLED", "429, RATE_LIMITED", "500, UNAVAILABLE", "503, UNAVAILABLE", "404, UNAVAILABLE")
    fun `a documented error status becomes its problem`(code: Int, problem: BalanceProblem) {
        status = code
        body = """{"error":"nope"}"""

        assertEquals(BalanceRead.Unavailable(problem), client().read(RpcAccountName.PRIMARY))
    }

    @ParameterizedTest
    @CsvSource(
        "not json",
        "'{}'",
        "'{\"creditsRemaining\":\"many\",\"creditsUsed\":1}'",
        "'{\"creditsRemaining\":-1,\"creditsUsed\":1,\"creditCycle\":{\"end\":\"2026-12-05\"},\"subscriptionDetails\":{\"creditsLimit\":10}}'",
        "'{\"creditsRemaining\":1,\"creditsUsed\":1,\"creditCycle\":{\"end\":\"soon\"},\"subscriptionDetails\":{\"creditsLimit\":10}}'",
        "'{\"creditsRemaining\":1,\"creditsUsed\":1,\"creditCycle\":{\"end\":\"2026-12-05\"}}'",
    )
    fun `a body that cannot be read is malformed`(text: String) {
        body = text

        assertEquals(BalanceRead.Unavailable(BalanceProblem.MALFORMED), client().read(RpcAccountName.PRIMARY))
    }

    @Test
    fun `an empty body on a good status is malformed`() {
        body = ""

        assertEquals(BalanceRead.Unavailable(BalanceProblem.MALFORMED), client().read(RpcAccountName.PRIMARY))
    }

    @Test
    fun `a limit of zero is a readable balance`() {
        body = usage(remaining = "0", used = "0", limit = "0")

        assertEquals(0L, assertIs<BalanceRead.Read>(client().read(RpcAccountName.PRIMARY)).balance.creditsLimit)
    }

    @Test
    fun `an account with no project id sends no request`() {
        val configs = RpcAccountConfigs(listOf(RpcAccountTestData.primary(projectId = null)))

        assertEquals(BalanceRead.Unavailable(BalanceProblem.NO_PROJECT_ID), client(configs).read(RpcAccountName.PRIMARY))
        assertEquals(0, requests.size)
        assertEquals(0, calls.size)
    }

    @ParameterizedTest
    @CsvSource("https://rpc.example.test/", "https://rpc.example.test/?foo=bar", "https://rpc.example.test/?api-key=", "https://rpc.example.test/?apikey=abc")
    fun `an rpc url without an api-key sends no request`(url: String) {
        val configs = RpcAccountConfigs(listOf(RpcAccountTestData.primary(url = url)))

        assertEquals(BalanceRead.Unavailable(BalanceProblem.NO_API_KEY), client(configs).read(RpcAccountName.PRIMARY))
        assertEquals(0, requests.size)
    }

    @Test
    fun `the api key is decoded from the rpc url`() {
        assertEquals("a b+c", HeliusApiKeys.fromRpcUrl("https://x.test/?x=1&api-key=a%20b%2Bc"))
        assertNull(HeliusApiKeys.fromRpcUrl("not a url with spaces"))
    }

    @Test
    fun `a read that times out is unavailable and neither the answer nor the log holds a key or project id`() {
        hang = true
        LogCapture().use { logs ->
            val read = client(readTimeout = Duration.ofMillis(300)).read(RpcAccountName.PRIMARY)

            assertEquals(BalanceRead.Unavailable(BalanceProblem.UNAVAILABLE), read)
            assertEquals(ApiCallOutcome.FAILED, calls.single().outcome)
            assertFalse(logs.everything().contains(PRIMARY_KEY) || logs.everything().contains(PRIMARY_PROJECT))
            assertEquals(emptyList(), logs.at(Level.ERROR))
        }
    }

    @Test
    fun `a refused connection is unavailable`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        assertEquals(BalanceRead.Unavailable(BalanceProblem.UNAVAILABLE), client(base = "http://127.0.0.1:$closedPort").read(RpcAccountName.PRIMARY))
    }

    @Test
    fun `each request is recorded once as the admin API's project usage, by outcome`() {
        client().read(RpcAccountName.PRIMARY)
        status = 429
        client().read(RpcAccountName.PRIMARY)
        status = 401
        client().read(RpcAccountName.PRIMARY)
        status = 200
        body = "not json"
        client().read(RpcAccountName.PRIMARY)

        assertEquals(listOf(ApiCallOutcome.OK, ApiCallOutcome.RATE_LIMITED, ApiCallOutcome.FAILED, ApiCallOutcome.FAILED), calls.map { it.outcome })
        assertEquals(setOf(ApiProvider.HELIUS_ADMIN), calls.map { it.provider }.toSet())
        assertEquals(setOf("projectUsage"), calls.map { it.operation }.toSet())
        assertEquals(setOf(ApiTask.ACCOUNT_BALANCE), calls.map { it.context.task }.toSet())
    }

    @Test
    fun `a base url with surrounding space is used trimmed`() {
        val read = client(base = "  $baseUrl/ \n").read(RpcAccountName.PRIMARY)

        assertIs<BalanceRead.Read>(read)
        assertEquals("/v0/admin/projects/$PRIMARY_PROJECT/usage", requests.single().path)
        assertEquals(1, calls.size)
    }

    @Test
    fun `an address that cannot be built is unavailable, sends and records nothing, and logs neither key nor project id`() {
        LogCapture().use { logs ->
            val read = client(base = "http://exa mple.test").read(RpcAccountName.PRIMARY)

            assertEquals(BalanceRead.Unavailable(BalanceProblem.UNAVAILABLE), read)
            assertEquals(0, requests.size)
            assertEquals(0, calls.size)
            assertFalse(logs.everything().contains(PRIMARY_PROJECT) || logs.everything().contains(PRIMARY_KEY))
        }
    }

    @Test
    fun `an api key the JDK refuses in a header is unavailable, sends and records nothing, and is never logged`() {
        val configs = RpcAccountConfigs(listOf(RpcAccountTestData.primary(url = "https://primary.example.test/?api-key=bad%01key-value")))
        LogCapture().use { logs ->
            val read = client(configs).read(RpcAccountName.PRIMARY)

            assertEquals(BalanceRead.Unavailable(BalanceProblem.UNAVAILABLE), read)
            assertEquals(0, requests.size)
            assertEquals(0, calls.size)
            assertFalse(logs.everything().contains("key-value"))
        }
    }

    @Test
    fun `a body that is the JSON literal null is malformed and the request is recorded`() {
        body = "null"

        assertEquals(BalanceRead.Unavailable(BalanceProblem.MALFORMED), client().read(RpcAccountName.PRIMARY))
        assertEquals(listOf(ApiCallOutcome.FAILED), calls.map { it.outcome })
    }

    @Test
    fun `prepaid credits are added to the plan's and floored once`() {
        body = """{"creditsRemaining":100.6,"prepaidCreditsRemaining":50.6,"creditsUsed":5,"creditCycle":{"end":"2026-12-05"},"subscriptionDetails":{"creditsLimit":1000,"plan":"Free"}}"""

        assertEquals(151L, assertIs<BalanceRead.Read>(client().read(RpcAccountName.PRIMARY)).balance.creditsRemaining)
    }

    @Test
    fun `absent prepaid credits count as zero`() {
        body = """{"creditsRemaining":100.6,"creditsUsed":5,"creditCycle":{"end":"2026-12-05"},"subscriptionDetails":{"creditsLimit":1000,"plan":"Free"}}"""

        assertEquals(100L, assertIs<BalanceRead.Read>(client().read(RpcAccountName.PRIMARY)).balance.creditsRemaining)
    }

    @Test
    fun `the usage page gives the admin API's calls a purpose`() {
        assertEquals(ApiPurpose.CREDIT_BALANCE, ApiPurpose.of(ApiProvider.HELIUS_ADMIN, HeliusUsageClient.OPERATION))
    }

    private fun usage(remaining: String, used: String, limit: String) =
        """{"creditsRemaining":$remaining,"creditsUsed":$used,"creditCycle":{"start":"2026-11-05","end":"2026-12-05"},"subscriptionDetails":{"creditsLimit":$limit,"plan":"Free"}}"""

    private companion object {
        val USAGE = """
            {"creditsRemaining":750000,"creditsUsed":250000,
             "creditCycle":{"start":"2026-11-05","end":"2026-12-05"},
             "subscriptionDetails":{"billingCycle":null,"creditsLimit":1000000,"plan":"Free"}}
        """.trimIndent()
    }
}
