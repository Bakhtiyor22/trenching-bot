package com.trenching.bot.config

import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ReplayPersistenceConfiguration {
    @Bean
    fun simulationClock(): Clock = Clock.systemUTC()
}
