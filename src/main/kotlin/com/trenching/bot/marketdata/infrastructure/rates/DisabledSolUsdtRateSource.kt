package com.trenching.bot.marketdata.infrastructure.rates

import com.trenching.bot.marketdata.RateLookup
import com.trenching.bot.marketdata.RateUnavailableReason
import com.trenching.bot.marketdata.SolUsdtRateSource
import java.time.Instant

/** The rate source when `marketdata.reference-rates.enabled` is false: every rate is `NOT_CONFIGURED`. */
class DisabledSolUsdtRateSource : SolUsdtRateSource {
    override fun minuteRates(minutes: Set<Instant>): Map<Instant, RateLookup> = minutes.associateWith { NOT_CONFIGURED }

    override fun current(): RateLookup = NOT_CONFIGURED

    private companion object {
        val NOT_CONFIGURED = RateLookup.Unavailable(RateUnavailableReason.NOT_CONFIGURED)
    }
}
