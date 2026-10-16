package com.trenching.bot.apiusage

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/** How often counted requests are written to the database; a hard kill loses at most one interval of counts. */
@ConfigurationProperties("trenching.api-usage")
data class ApiUsageProperties(val flushInterval: Duration = Duration.ofSeconds(15)) {
    init {
        require(flushInterval >= MIN_FLUSH_INTERVAL && flushInterval <= MAX_FLUSH_INTERVAL) {
            "trenching.api-usage.flush-interval must be between 1 and 300 seconds"
        }
    }

    private companion object {
        val MIN_FLUSH_INTERVAL: Duration = Duration.ofSeconds(1)
        val MAX_FLUSH_INTERVAL: Duration = Duration.ofSeconds(300)
    }
}
