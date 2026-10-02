package com.trenching.bot.support

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "trenching.tests.postgres", name = ["provider"], havingValue = "native")
class NativePostgresTestConfiguration {
    @Bean(destroyMethod = "close")
    fun nativePostgresTestServer(environment: Environment): NativePostgresTestServer =
        NativePostgresTestServer.start(environment.getRequiredProperty("trenching.tests.postgres.binary-directory"))

    @Bean
    fun nativePostgresConnectionDetails(server: NativePostgresTestServer): JdbcConnectionDetails =
        object : JdbcConnectionDetails {
            override fun getUsername(): String = NativePostgresTestServer.USERNAME
            override fun getPassword(): String = NativePostgresTestServer.PASSWORD
            override fun getJdbcUrl(): String = server.jdbcUrl
        }
}
