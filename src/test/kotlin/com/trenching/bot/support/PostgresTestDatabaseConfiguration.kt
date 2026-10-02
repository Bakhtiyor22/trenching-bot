package com.trenching.bot.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import

@TestConfiguration(proxyBeanMethods = false)
@Import(PostgresTestContainerConfiguration::class, NativePostgresTestConfiguration::class)
class PostgresTestDatabaseConfiguration
