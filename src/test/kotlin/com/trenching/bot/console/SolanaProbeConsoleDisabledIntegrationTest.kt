package com.trenching.bot.console

import com.trenching.bot.console.solana.SolanaConsoleHttp
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import kotlin.test.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@ActiveProfiles("test")
@Import(PostgresTestDatabaseConfiguration::class)
class SolanaProbeConsoleDisabledIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `the solana endpoints do not exist while the console flag is absent`() {
        val http = SolanaConsoleHttp(port, objectMapper)

        assertEquals(404, http.get("/api/solana/status").first)
        assertEquals(404, http.post("/api/solana/check").first)
        assertEquals(404, http.post("/api/solana/address-check", "{}").first)
    }
}
