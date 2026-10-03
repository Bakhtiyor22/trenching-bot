package com.trenching.bot.console

import com.trenching.bot.console.solana.SolanaConsoleHttp
import com.trenching.bot.console.solana.SolanaRpcTestData.TRACKED_WALLET
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["trenching.console.enabled=true", "trenching.solana.rpc-url="],
)
@Tag("integration")
@ActiveProfiles("test")
@Import(PostgresTestDatabaseConfiguration::class)
class SolanaProbeApiIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val http by lazy { SolanaConsoleHttp(port, objectMapper) }

    @Test
    fun `status reports an unconfigured rpc without a host`() {
        val (status, body) = http.get("/api/solana/status")

        assertEquals(200, status)
        assertEquals(false, body.path("configured").asBoolean())
        assertTrue(body.path("host").isNull)
        assertTrue(body.path("cluster").isNull)
    }

    @Test
    fun `check reports not configured and health stays up`() {
        val (status, body) = http.post("/api/solana/check")
        val (healthStatus, health) = http.get("/actuator/health")

        assertEquals(200, status)
        assertEquals("NOT_CONFIGURED", body.path("outcome").asString())
        assertEquals(200, healthStatus)
        assertEquals("UP", health.path("status").asString())
    }

    @Test
    fun `address check is a conflict when the rpc is not configured`() {
        val (status, body) = http.post("/api/solana/address-check", """{"address":"$TRACKED_WALLET"}""")

        assertEquals(409, status)
        assertEquals("SOLANA_NOT_CONFIGURED", body.path("code").asString())
        assertTrue(body.path("message").asString().isNotBlank())
        assertTrue(body.path("fields").isObject)
    }

    @Test
    fun `address check rejects an invalid address with a field error`() {
        val (status, body) = http.post("/api/solana/address-check", """{"address":"0OIl"}""")

        assertEquals(400, status)
        assertEquals("INVALID_ADDRESS", body.path("code").asString())
        assertTrue(body.path("fields").path("address").asString().isNotBlank())
    }

    @Test
    fun `address check rejects a body without an address`() {
        val (status, body) = http.post("/api/solana/address-check", "{}")

        assertEquals(400, status)
        assertEquals("INVALID_REQUEST", body.path("code").asString())
    }
}
