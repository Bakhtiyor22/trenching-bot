package com.trenching.bot.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Starts a throwaway PostgreSQL instance for integration tests.
 *
 * Import it into any test that needs the real datasource; Spring Boot wires the
 * connection details automatically via [ServiceConnection].
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestContainerConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
}
