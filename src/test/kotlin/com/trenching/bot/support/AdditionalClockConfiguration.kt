package com.trenching.bot.support

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@TestConfiguration
class AdditionalClockConfiguration {
    @Bean
    fun auditClock(): Clock = Clock.fixed(Instant.parse("1900-01-01T00:00:00Z"), ZoneOffset.UTC)
}
