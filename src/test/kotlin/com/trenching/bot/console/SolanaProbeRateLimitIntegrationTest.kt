package com.trenching.bot.console

import com.trenching.bot.console.solana.FakeSolanaRpcServer
import com.trenching.bot.console.solana.SolanaConsoleHttp
import com.trenching.bot.console.solana.SolanaRpcTestData.SECRET
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["trenching.console.enabled=true"],
)
@Tag("integration")
@ActiveProfiles("test")
@Import(PostgresTestDatabaseConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class SolanaProbeRateLimitIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `a 429 is reported after one request and the repeat within the ttl is served from cache`(output: CapturedOutput) {
        val http = SolanaConsoleHttp(port, objectMapper)

        val (firstStatus, first) = http.post("/api/solana/check")
        val (secondStatus, second) = http.post("/api/solana/check")
        val (_, status) = http.get("/api/solana/status")

        assertEquals(200, firstStatus)
        assertEquals("RATE_LIMITED", first.path("outcome").asString())
        assertEquals(false, first.path("cached").asBoolean())
        assertEquals(200, secondStatus)
        assertEquals("RATE_LIMITED", second.path("outcome").asString())
        assertEquals(true, second.path("cached").asBoolean())
        assertEquals(1, rpc.requestCount)
        assertEquals("127.0.0.1", status.path("host").asString())
        assertEquals("RATE_LIMITED", status.path("lastCheck").path("outcome").asString())
        listOf(first, second, status).forEach { assertFalse(it.toString().contains(SECRET)) }
        assertFalse(output.all.contains(SECRET))
    }

    companion object {
        private val rpc = FakeSolanaRpcServer.start().apply { respondWith(429) }

        @JvmStatic
        @DynamicPropertySource
        fun solanaRpc(registry: DynamicPropertyRegistry) {
            registry.add("trenching.solana.rpc-url") { rpc.url() }
        }

        @JvmStatic
        @AfterAll
        fun stopRpc() {
            rpc.close()
        }
    }
}
