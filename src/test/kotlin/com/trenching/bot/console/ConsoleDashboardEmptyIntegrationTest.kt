package com.trenching.bot.console

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

/** A context of its own (the extra property keeps it out of the shared cache), so its database is empty. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["trenching.console.enabled=true", "spring.application.name=console-dashboard-empty"],
)
@Tag("integration")
@ActiveProfiles("test")
@Import(PostgresTestDatabaseConfiguration::class)
class ConsoleDashboardEmptyIntegrationTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var json: ObjectMapper

    @Test
    fun `the dashboard of an empty database is zeros and empty lists`() {
        val http = ConsoleHttp(port, json)

        val dashboard = http.get("/api/dashboard")
        val datasets = http.get("/api/datasets").body
        val runs = http.get("/api/runs").body

        assertEquals(200, dashboard.status, dashboard.raw)
        assertEquals("UP", dashboard.body.path("health").path("status").asString())
        assertEquals(0, dashboard.body.path("runs").path("total").asLong())
        assertEquals(0, dashboard.body.path("runs").path("recent").size())
        assertTrue(dashboard.body.path("runs").path("last24h").isNumber)
        assertEquals(0, dashboard.body.path("runs").path("last24h").asLong())
        assertEquals(0, dashboard.body.path("scenarios").path("filled").asLong())
        assertEquals(0, dashboard.body.path("scenarios").path("rejected").asLong())
        assertEquals(0, dashboard.body.path("positions").path("open").asLong())
        assertEquals(0, dashboard.body.path("positions").path("closed").asLong())
        assertTrue(dashboard.body.path("realizedPnlByQuoteMint").isArray)
        assertEquals(0, dashboard.body.path("realizedPnlByQuoteMint").size())
        assertEquals(6, dashboard.body.path("rejectionsByReason").size())
        assertTrue(dashboard.body.path("rejectionsByReason").all { it.path("count").asLong() == 0L })
        assertEquals(0, datasets.path("total").asLong())
        assertEquals(0, datasets.path("items").size())
        assertEquals(0, runs.path("total").asLong())
    }
}
