package com.trenching.bot.console.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** The console HTTP API is registered only when [enabled]; it has no authentication, so it stays off in prod. */
@ConfigurationProperties(ConsoleProperties.PREFIX)
data class ConsoleProperties(val enabled: Boolean = false) {
    companion object {
        const val PREFIX = "trenching.console"
    }
}
