package com.trenching.bot.apiusage

/** Why a call is made, keyed by provider and operation; the console holds the wording for each code. */
enum class ApiPurpose {
    READ_TRANSACTION,
    LIST_SIGNATURES,
    MAINNET_CHECK,
    MINT_PROGRAM_CHECK,
    CONNECTION_CHECK,
    SLOT_PACE,
    MINUTE_RATES,
    LIVE_RATE,
    UNKNOWN;

    companion object {
        private val CATALOG = mapOf(
            ApiProvider.SOLANA_RPC to mapOf(
                "getTransaction" to READ_TRANSACTION,
                "getSignaturesForAddress" to LIST_SIGNATURES,
                "getGenesisHash" to MAINNET_CHECK,
                "getAccountInfo" to MINT_PROGRAM_CHECK,
                "getHealth" to CONNECTION_CHECK,
                "getVersion" to CONNECTION_CHECK,
                "getSlot" to CONNECTION_CHECK,
                "getRecentPerformanceSamples" to SLOT_PACE,
            ),
            ApiProvider.BINANCE to mapOf(
                "/api/v3/klines" to MINUTE_RATES,
                "/api/v3/ticker/price" to LIVE_RATE,
            ),
        )

        fun of(provider: ApiProvider, operation: String): ApiPurpose = CATALOG[provider]?.get(operation) ?: UNKNOWN
    }
}
