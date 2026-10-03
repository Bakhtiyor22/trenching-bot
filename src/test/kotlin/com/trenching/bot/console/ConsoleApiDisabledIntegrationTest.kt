package com.trenching.bot.console

import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import kotlin.test.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@ActiveProfiles("test")
@Import(PostgresTestDatabaseConfiguration::class)
class ConsoleApiDisabledIntegrationTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var environment: Environment

    @Test
    fun `without the console flag the api does not exist and health stays up`() {
        val http = ConsoleHttp(port, json)

        listOf(
            "/api/runs", "/api/datasets", "/api/dashboard", "/api/config/defaults",
            "/console", "/console/", "/console/index.html", "/console/assets/console-test.js", "/console/runs/sample",
        ).forEach { path ->
            assertEquals(404, http.get(path).status, path)
        }
        assertEquals(404, http.post("/api/runs", "{}").status)
        val health = http.get("/actuator/health")
        assertEquals(200, health.status)
        assertEquals("UP", health.body.path("status").asString())
    }

    @Test
    fun `uploads are limited to 20 MB`() {
        assertEquals("20MB", environment.getProperty("spring.servlet.multipart.max-file-size"))
        assertEquals("20MB", environment.getProperty("spring.servlet.multipart.max-request-size"))
    }
}
