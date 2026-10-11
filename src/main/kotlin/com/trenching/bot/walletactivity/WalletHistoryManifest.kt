package com.trenching.bot.walletactivity

/**
 * [listingAddress] is the address whose signature listing discovery scanned, derived from [scope] and [wallet], and for
 * [WalletHistoryScope.WALLET_TOKEN_ACCOUNT] also from [targetMint] and [tokenProgram], the program that owned the mint when the
 * capture started. The three token fields are set exactly for that scope.
 */
data class WalletHistoryManifest(
    val schemaVersion: Int,
    val wallet: String,
    val scope: WalletHistoryScope = WalletHistoryScope.WALLET_REFERENCES,
    val listingAddress: String = wallet,
    val genesisHash: String,
    val commitment: String,
    val capturedAt: String,
    val limit: Int,
    val scanPageLimit: Int,
    val selection: List<WalletHistorySelection>,
    val records: List<WalletHistoryRecord>,
    val complete: Boolean,
    val discoveryComplete: Boolean,
    val discoveryStop: WalletHistoryDiscoveryStop?,
    val scannedSignatures: Int,
    val skippedFailed: Int,
    val scanCursor: WalletHistoryScanCursor?,
    val requestsUsed: Int,
    val bytesUsed: Long,
    val targetMint: String? = null,
    val tokenProgram: String? = null,
    val verification: WalletTokenVerification? = null,
)

data class WalletHistorySelection(val signature: String, val slot: Long)

data class WalletHistoryScanCursor(val signature: String, val slot: Long)

enum class WalletHistoryDiscoveryStop {
    LIMIT_REACHED,
    HISTORY_END,
    SCAN_BOUND,
}

data class WalletHistoryRecord(
    val signature: String,
    val slot: Long,
    val outcome: WalletHistoryOutcome,
    val artifact: String? = null,
    val sha256: String? = null,
    val bytes: Long = 0,
)

enum class WalletHistoryOutcome {
    PENDING,
    UNAVAILABLE,
    FAILED,
    NON_SIGNER,
    UNCLASSIFIED,
    UNSUPPORTED_VERSION,
    INVALID_TRANSACTION,
    PROVIDER_UNAVAILABLE,
    WITHHELD_UNSAFE_TEXT,

    /** Listed under a scope other than the wallet itself, but the wallet is not among its accounts. */
    WALLET_ABSENT,
}
