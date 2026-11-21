package com.trenching.bot.console.capture

import com.trenching.bot.chaincapture.ProviderFailureMeanings

/**
 * The plain clause for each code a capture operation can stop with, written so a caller can say `"Capture step stopped: {}."`.
 * The provider categories reuse [ProviderFailureMeanings]. Every clause is lower case and has no full stop. A code that is not listed gets the fixed unknown clause; the caller still logs `code=`.
 */
object CaptureStopMeanings {
    private const val BUDGET_EXHAUSTED_PREFIX = "BUDGET_EXHAUSTED_"
    private const val UNKNOWN = "an unexpected stop code; see the capture console"

    private val clauses: Map<String, String> = ProviderFailureMeanings.clauses + mapOf(
        "REQUESTS" to "the operation's request budget ran out",
        "BYTES" to "the operation's download budget ran out",
        "RESPONSE_BYTES" to "a single provider response was larger than the capture allows",
        "DEADLINE" to "the operation's time limit ran out",
        "BUDGET_EXHAUSTED" to "the capture's total request or download budget across all its attempts ran out",
        "RETRY_EXHAUSTED" to "the capture used all its allowed attempts",
        "TIMEOUT" to "the provider did not answer in time",
        "NOT_CONFIGURED" to "the provider is not configured",
        "INTERRUPTED" to "the capture was interrupted",
        "STOP_REQUESTED" to "a stop was requested while transactions were being fetched",
        "OVER_CAP" to "the market window lists more trades than the capture's trade cap, so none were fetched",
        "HISTORY_NOT_EXHAUSTED" to "the wallet's token-account history was cut off by the sample or page limit, so older transactions may exist",
        "HORIZON_NOT_ELAPSED" to "a market window has not ended on chain yet, so it was not listed",
        "MARKET_HORIZON_NOT_ELAPSED" to "a market window has not ended on chain yet; a later resume finishes it",
        "MARKET_LISTED_BEFORE_END" to "a market window was listed before it ended and misses its later trades",
        "MARKET_WINDOW_INVALID" to "the buy is too early for its market window, which would start before slot 0",
        "CAPTURE_FAILED" to "the capture failed unexpectedly",
        "PROCESS_RESTART" to "the application restarted while it ran",
        "PAUSED_BY_USER" to "it was paused from the console",
        "CANCELLED_BY_USER" to "it was cancelled from the console",
        "STALE_ATTEMPT" to "its capture attempt is no longer the selected one",
        "PREREQUISITE" to "an earlier capture step it needs is not ready",
        "RESUME_IDENTITY" to "the saved capture was made with different settings",
        "MAINNET_REQUIRED" to "the provider is not Solana mainnet",
        "ENDPOINT" to "the provider endpoint is not valid",
        "ENDPOINT_TLS" to "a remote provider endpoint must use https",
        "TIMEOUTS" to "the provider timeouts are not valid",
        "PACING" to "the provider request pacing is not valid",
        "RPC_METHOD" to "the call is not a permitted read method",
        "SLOT_RESPONSE" to "the provider's slot answer is not valid",
        "BLOCK_TIME_RESPONSE" to "the provider's block time answer is not valid",
        "PERFORMANCE_RESPONSE" to "the provider's performance samples are not valid",
        "BLOCKS_RESPONSE" to "the provider's block list is not valid",
        "ACCOUNT_INFO" to "the provider's account answer is not valid",
        "MINT_NOT_FOUND" to "the token's mint account does not exist",
        "ACCOUNT_OWNER_ENCODING" to "the token account's owner is not a valid address",
        "SIGNATURE_PAGE" to "the provider's signature page is not valid",
        "SIGNATURE_ENTRY" to "a signature entry from the provider is not valid",
        "SIGNATURE_ENCODING" to "a signature from the provider is not valid",
        "SIGNATURE_ORDER" to "the provider listed signatures out of order",
        "NON_PROGRESSING_PAGE" to "the provider returned the same page again",
        "CONFLICTING_SIGNATURE" to "the provider listed the same signature with different details",
        "REQUEST_BOUNDS" to "the capture's request bounds are not valid",
        "MANIFEST_MISSING" to "the saved capture has no record of its earlier steps",
        "MARKET_CASE_NOT_FOUND" to "the saved market capture has no window for this buy",
        "MARKET_CASE_INCOMPLETE" to "the market capture for this buy is not complete",
        "CAPTURE_INCOMPLETE" to "an earlier capture step it needs is not complete",
        "CAPTURE_CHANGED" to "the saved capture changed since it was verified",
        "WALLET_MISMATCH" to "the saved capture belongs to another wallet",
        "SAME_DIRECTORY" to "the capture would overwrite another step's files",
        "NO_OBSERVATIONS" to "the market capture has no trades to price",
        "REFERENCE_RATES_INCOMPLETE" to "the reference rates are incomplete",
        "CUTOFF_DECISION" to "the exit cutoff could not be decided",
        "VERIFICATION_TARGET" to "the first-buy verification does not match its target",
        "VERIFICATION_RESULT" to "the first-buy verification result does not match its evidence",
        "VERIFICATION_LISTING" to "the first-buy verification found the token already listed",
        "CASE_RESULT_MISMATCH" to "the step returned a result for another operation",
        "CASE_OPERATION_UNSUPPORTED" to "the case does not support this operation",
        "MARKET_CASE_IDENTITY_MISMATCH" to "the saved market window belongs to another buy",
    )

    internal val codes: Set<String> get() = clauses.keys

    fun meaning(code: String): String {
        clauses[code]?.let { return it }
        if (!code.startsWith(BUDGET_EXHAUSTED_PREFIX)) return UNKNOWN
        return clauses[code.removePrefix(BUDGET_EXHAUSTED_PREFIX)] ?: UNKNOWN
    }
}
