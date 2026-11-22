package com.trenching.bot.console.solana.accounts.infrastructure.helius

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.math.BigDecimal

/** The documented fields of the Admin API's project usage answer; anything else in it is ignored. Credits are read as decimals, never as doubles. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HeliusUsageResponse(
    val creditsRemaining: BigDecimal? = null,
    val prepaidCreditsRemaining: BigDecimal? = null,
    val creditsUsed: BigDecimal? = null,
    val creditCycle: CreditCycle? = null,
    val subscriptionDetails: SubscriptionDetails? = null,
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CreditCycle(val start: String? = null, val end: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SubscriptionDetails(val creditsLimit: BigDecimal? = null, val plan: String? = null)
}
