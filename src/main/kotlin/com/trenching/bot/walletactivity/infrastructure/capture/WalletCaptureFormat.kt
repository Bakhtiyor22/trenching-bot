package com.trenching.bot.walletactivity.infrastructure.capture

import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.walletactivity.VerificationOutcome
import com.trenching.bot.walletactivity.WalletHistoryCaptureLimits
import com.trenching.bot.walletactivity.WalletHistoryManifest
import com.trenching.bot.walletactivity.WalletHistoryOutcome
import com.trenching.bot.walletactivity.WalletHistoryScope

internal object WalletCaptureFormat {
    const val VERSION = 3

    /** Version 2 predates scopes; it is read as [WalletHistoryScope.WALLET_REFERENCES]. */
    const val SCOPELESS_VERSION = 2

    /** Only [WalletHistoryScope.WALLET_TOKEN_ACCOUNT] manifests, which add the target mint, its token program and verification. */
    const val TOKEN_SCOPE_VERSION = 4
    val READABLE_VERSIONS = setOf(SCOPELESS_VERSION, VERSION, TOKEN_SCOPE_VERSION)
    const val MAX_SELECTION = WalletHistoryCaptureLimits.MAX_TRANSACTIONS
    const val MAX_SCAN_PAGES = WalletHistoryCaptureLimits.MAX_SCAN_PAGES
    const val MAX_CAPTURE_BYTES = WalletHistoryCaptureLimits.MAX_BYTES
    const val MAX_REQUESTS = WalletHistoryCaptureLimits.MAX_REQUESTS
    const val MANIFEST = "manifest.json"

    /** Detail fetches between manifest checkpoints; a resume refetches any newer, unreferenced artifact. */
    const val DETAIL_CHECKPOINT_INTERVAL = 25
    val UNRESOLVED_OUTCOMES = setOf(WalletHistoryOutcome.PENDING, WalletHistoryOutcome.PROVIDER_UNAVAILABLE, WalletHistoryOutcome.INVALID_TRANSACTION)
    val ARTIFACT_OUTCOMES = setOf(WalletHistoryOutcome.FAILED, WalletHistoryOutcome.NON_SIGNER, WalletHistoryOutcome.UNCLASSIFIED)

    /** Where verification artifacts live, apart from the token-account records' `transactions/`. */
    const val VERIFICATION_DIRECTORY = "verification"

    /** Two full accumulator scans of in-range entries plus every creation candidate. */
    const val MAX_VERIFICATION_RECORDS =
        2 * WalletHistoryCaptureLimits.VERIFICATION_LISTING_PAGES * ChainCaptureFormat.SIGNATURE_PAGE_SIZE + WalletHistoryCaptureLimits.CREATION_FETCHES
    val UNRESOLVED_VERIFICATION_OUTCOMES = setOf(VerificationOutcome.PENDING, VerificationOutcome.INVALID_TRANSACTION, VerificationOutcome.PROVIDER_UNAVAILABLE)

    /** Discovery stopped and every selected record has a final outcome: step 1 of a token-account capture is done. */
    fun recordsResolved(manifest: WalletHistoryManifest): Boolean =
        manifest.discoveryComplete && manifest.records.none { it.outcome in UNRESOLVED_OUTCOMES }

    /**
     * A capture is complete once its records are resolved; a token-account capture also once verification has nothing to
     * verify or has reached its verdict.
     */
    fun isComplete(manifest: WalletHistoryManifest): Boolean = recordsResolved(manifest) && when (manifest.scope) {
        WalletHistoryScope.WALLET_REFERENCES, WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR -> true
        WalletHistoryScope.WALLET_TOKEN_ACCOUNT -> manifest.verification?.let { it.firstBuy == null || it.result != null } ?: false
    }

    /** The version a manifest of [scope] is written in; the existing scopes keep writing [VERSION]. */
    fun version(scope: WalletHistoryScope): Int = when (scope) {
        WalletHistoryScope.WALLET_REFERENCES, WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR -> VERSION
        WalletHistoryScope.WALLET_TOKEN_ACCOUNT -> TOKEN_SCOPE_VERSION
    }
}
