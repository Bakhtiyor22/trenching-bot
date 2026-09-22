package com.trenching.bot.health

import com.trenching.bot.support.PostgresTestContainerConfiguration
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration::class)
class HealthEndpointTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `health endpoint reports the application and its database as up`() {
        val response = RestClient.create()
            .get()
            .uri("http://localhost:$port/actuator/health")
            .retrieve()
            .body(String::class.java)

        val health = objectMapper.readTree(response)

        assertEquals("UP", health.path("status").asString())
        assertEquals("UP", health.path("components").path("db").path("status").asString())
    }
}
