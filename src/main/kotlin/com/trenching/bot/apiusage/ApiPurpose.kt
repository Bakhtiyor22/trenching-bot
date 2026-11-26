package com.trenching.bot.apiusage

/** Why a call is made, keyed by provider and operation; the console holds the wording for each code. */
enum class ApiPurpose {
    READ_TRANSACTION,
    LIST_SIGNATURES,
    MAINNET_CHECK,
    MINT_PROGRAM_CHECK,
    CONNECTION_CHECK,
    SLOT_PACE,
    BLOCK_TIME,
    MINUTE_RATES,
    LIVE_RATE,
    SEND_NOTIFICATION,
    CREDIT_BALANCE,
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
                "getBlockTime" to BLOCK_TIME,
                "getBlocks" to BLOCK_TIME,
            ),
            ApiProvider.BINANCE to mapOf(
                "/api/v3/klines" to MINUTE_RATES,
                "/api/v3/ticker/price" to LIVE_RATE,
            ),
            ApiProvider.HELIUS_ADMIN to mapOf(
                "projectUsage" to CREDIT_BALANCE,
            ),
            ApiProvider.TELEGRAM to mapOf(
                "sendMessage" to SEND_NOTIFICATION,
            ),
        )

        fun of(provider: ApiProvider, operation: String): ApiPurpose = CATALOG[provider]?.get(operation) ?: UNKNOWN
    }
}
