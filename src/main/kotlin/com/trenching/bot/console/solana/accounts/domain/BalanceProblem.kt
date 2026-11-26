package com.trenching.bot.console.solana.accounts.domain

/** Why an account's balance could not be read. Stored by name, so a name never changes meaning; [meaning] reads inside "Could not read the balance: {}". */
enum class BalanceProblem(val meaning: String) {
    NO_PROJECT_ID("no project id is configured for the account"),
    NO_API_KEY("the account's RPC URL carries no api-key"),
    INVALID_PROJECT("the provider says the project id does not match the key"),
    AUTHENTICATION("the provider refused the key"),
    ADMIN_API_DISABLED("the provider has not enabled its admin API for the project"),
    RATE_LIMITED("the provider is rate limiting the balance read"),
    UNAVAILABLE("the provider could not be reached"),
    MALFORMED("the provider's answer could not be read"),
}
