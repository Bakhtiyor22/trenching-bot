package com.trenching.bot.console.solana

import com.trenching.bot.apiusage.ApiCall
import com.trenching.bot.apiusage.ApiCallOutcome
import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.console.solana.SolanaRpcTestData.DEVNET_GENESIS
import com.trenching.bot.console.solana.SolanaRpcTestData.RPC_HOST
import com.trenching.bot.console.solana.SolanaRpcTestData.RPC_URL
import com.trenching.bot.console.solana.SolanaRpcTestData.SECRET
import com.trenching.bot.console.solana.SolanaRpcTestData.error
import com.trenching.bot.console.solana.SolanaRpcTestData.expectCall
import com.trenching.bot.console.solana.SolanaRpcTestData.mapper
import com.trenching.bot.console.solana.SolanaRpcTestData.properties
import com.trenching.bot.console.solana.SolanaRpcTestData.respond
import com.trenching.bot.console.solana.SolanaRpcTestData.result
import java.net.ServerSocket
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

@ExtendWith(OutputCaptureExtension::class)
class JsonRpcSolanaProbeTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val calls = mutableListOf<ApiCall>()
    private val probe = JsonRpcSolanaProbe(SolanaRpcEndpoint.from(RPC_URL), builder.build(), mapper) { calls += it }

    @Test
    fun `reports ok with the node version, confirmed slot, cluster and latency`() {
        server.respond("getHealth", result("\"ok\""))
        server.respond("getVersion", result("""{"solana-core":"3.0.6","feature-set":3294202862}"""))
        server.expectCall("getSlot")
            .andExpect(jsonPath("$.params[0].commitment").value("confirmed"))
            .andRespond(withSuccess(result("412345678"), MediaType.APPLICATION_JSON))
        server.respond("getGenesisHash", result("\"$DEVNET_GENESIS\""))

        val outcome = probe.check()

        server.verify()
        val ok = assertIs<ProbeOutcome.Ok>(outcome)
        assertEquals("3.0.6", ok.version)
        assertEquals(412_345_678L, ok.slot)
        assertEquals(SolanaCluster.DEVNET, ok.cluster)
        assertTrue(ok.latencyMs >= 0)
    }

    @Test
    fun `records every probe request as a connection check with its body bytes`() {
        val health = result("\"ok\"")
        server.respond("getHealth", health)
        server.respond("getVersion", result("""{"solana-core":"3.0.6","feature-set":3294202862}"""))
        server.respond("getSlot", result("412345678"))
        server.respond("getGenesisHash", result("\"$DEVNET_GENESIS\""))

        probe.check()

        assertEquals(listOf("getHealth", "getVersion", "getSlot", "getGenesisHash"), calls.map { it.operation })
        assertTrue(calls.all { it.provider == ApiProvider.SOLANA_RPC && it.outcome == ApiCallOutcome.OK })
        assertTrue(calls.all { it.context == ApiUsageContext(ApiTask.CONNECTION_CHECK, null) })
        assertEquals(health.length.toLong(), calls.first().responseBytes)
    }

    @Test
    fun `records a 429 as rate limited and a json-rpc error as failed`() {
        server.expectCall("getHealth").andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        server.respond("getHealth", error(-32005, "Node is unhealthy"))

        probe.check()
        probe.check()

        assertEquals(listOf(ApiCallOutcome.RATE_LIMITED, ApiCallOutcome.FAILED), calls.map { it.outcome })
    }

    @Test
    fun `classifies a json-rpc error under http 200 as an rpc error and stops`() {
        server.respond("getHealth", error(-32005, "Node is unhealthy"))

        val outcome = probe.check()

        server.verify()
        assertEquals(ProbeOutcome.RpcError(-32005, "Node is unhealthy"), outcome)
    }

    @Test
    fun `reports rate limited after exactly one request on http 429`() {
        server.expectCall("getHealth").andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

        val outcome = probe.check()

        server.verify()
        assertEquals(ProbeOutcome.RateLimited, outcome)
    }

    @Test
    fun `reports an http server error as unavailable`() {
        server.expectCall("getHealth").andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertEquals(ProbeOutcome.Unavailable("HTTP 503"), probe.check())
    }

    @Test
    fun `reports a body that is not json-rpc as unavailable`() {
        server.respond("getHealth", "<html>gateway</html>")

        assertEquals(ProbeOutcome.Unavailable("malformed response"), probe.check())
    }

    @Test
    fun `returns not configured without any http call`() {
        val unconfigured = JsonRpcSolanaProbe(SolanaRpcEndpoint.from(" "), builder.build(), mapper)

        val outcome = unconfigured.check()

        server.verify()
        assertEquals(ProbeOutcome.NotConfigured, outcome)
        assertNull(unconfigured.host)
    }

    @Test
    fun `reports a read timeout as timeout`() {
        FakeSolanaRpcServer.start().use { rpc ->
            rpc.hangUntilClosed()
            val realProbe = SolanaProbeConfiguration().solanaProbe(
                properties(rpc.url(), readTimeout = Duration.ofMillis(300)), mapper, ApiUsageRecorder.NONE)

            assertEquals(ProbeOutcome.Timeout, realProbe.check())
            assertEquals(1, rpc.requestCount)
        }
    }

    @Test
    fun `exposes only the host of an rpc url carrying an api key`() {
        assertEquals(RPC_HOST, probe.host)
        assertEquals(RPC_HOST, SolanaRpcEndpoint.from(RPC_URL).toString())
    }

    @Test
    fun `never echoes an api-key url in an outcome or a log line`(output: CapturedOutput) {
        server.respond("getHealth", error(-32600, "bad request for $RPC_URL with key $SECRET"))
        val echoed = probe.check()
        val refusedPort = ServerSocket(0).use { it.localPort }
        val refused = SolanaProbeConfiguration()
            .solanaProbe(properties("http://127.0.0.1:$refusedPort/?api-key=$SECRET"), mapper, ApiUsageRecorder.NONE)
            .check()

        val rpcError = assertIs<ProbeOutcome.RpcError>(echoed)
        assertFalse(rpcError.message.contains(SECRET))
        assertTrue(rpcError.message.contains(RPC_HOST))
        assertIs<ProbeOutcome.Unavailable>(refused)
        assertFalse(refused.toString().contains(SECRET))
        assertFalse(output.all.contains(SECRET))
    }

    @ParameterizedTest
    @CsvSource(
        "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG, DEVNET",
        "4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY, TESTNET",
        "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d, MAINNET_BETA",
        "11111111111111111111111111111111, UNKNOWN",
    )
    fun `names the cluster from the genesis hash`(genesisHash: String, cluster: SolanaCluster) {
        server.respond("getHealth", result("\"ok\""))
        server.respond("getVersion", result("""{"solana-core":"3.0.6"}"""))
        server.respond("getSlot", result("1"))
        server.respond("getGenesisHash", result("\"$genesisHash\""))

        val ok = assertIs<ProbeOutcome.Ok>(probe.check())

        assertEquals(cluster, ok.cluster)
    }
}
