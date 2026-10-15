package com.trenching.bot.marketdata.application

import com.trenching.bot.marketdata.MarketTransactionOutcome
import tools.jackson.databind.JsonNode

/** What one detail request returned: a classified transaction, or the provider's answer that its version is unsupported. */
internal sealed interface MarketDetailFetch {
    data class Fetched(val raw: JsonNode, val outcome: MarketTransactionOutcome) : MarketDetailFetch

    data object UnsupportedVersion : MarketDetailFetch
}
