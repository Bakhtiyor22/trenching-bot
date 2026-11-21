package com.trenching.bot.chaincapture

/**
 * The plain clause for each category a failed provider call can end with, written for `"RPC call failed after 3 attempts: {}."`.
 * Every clause is lower case with no full stop. The console's capture stop meanings reuse these clauses for the same codes.
 */
object ProviderFailureMeanings {
    private const val UNKNOWN = "the provider call failed"

    val clauses: Map<String, String> = mapOf(
        "IO" to "the connection to the provider failed",
        "RATE_LIMITED" to "the provider is rate limiting",
        "UNAVAILABLE" to "the provider is unavailable",
        "AUTHENTICATION" to "the provider refused the credentials",
        "HTTP" to "the provider answered with an unexpected HTTP status",
        "MALFORMED" to "the provider's answer could not be read",
        "RPC_ERROR" to "the provider returned an error for the call",
        "UNSUPPORTED_VERSION" to "the provider cannot read a transaction version in the window",
        "SLOT_SKIPPED" to "the provider has no block for a requested slot",
    )

    fun meaning(category: String): String = clauses[category] ?: UNKNOWN
}
