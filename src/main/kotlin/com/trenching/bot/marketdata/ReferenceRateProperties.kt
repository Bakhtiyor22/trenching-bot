package com.trenching.bot.marketdata

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/** Display-only SOL/USDT rates. Disabled unless [enabled]; then every lookup is `NOT_CONFIGURED` and nothing is called. */
@ConfigurationProperties("marketdata.reference-rates")
data class ReferenceRateProperties(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.binance.com",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(10),
    val tickerCacheTtl: Duration = Duration.ofSeconds(30),
    val maxCachedMinutes: Int = 100_000,
    val maxRequestsPerLookup: Int = 20,
) {
    init {
        require(baseUrl.startsWith("https://")) { "marketdata.reference-rates.base-url must be https" }
        require(connectTimeout.isPositive) { "marketdata.reference-rates.connect-timeout must be positive" }
        require(readTimeout.isPositive) { "marketdata.reference-rates.read-timeout must be positive" }
        require(!tickerCacheTtl.isNegative) { "marketdata.reference-rates.ticker-cache-ttl must not be negative" }
        require(maxCachedMinutes > 0) { "marketdata.reference-rates.max-cached-minutes must be positive" }
        require(maxRequestsPerLookup > 0) { "marketdata.reference-rates.max-requests-per-lookup must be positive" }
    }
}
