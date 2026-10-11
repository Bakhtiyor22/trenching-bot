package com.trenching.bot.walletactivity.infrastructure.capture

import com.trenching.bot.chaincapture.CapturedTransactionCheck
import com.trenching.bot.chaincapture.CapturedTransactionShape
import com.trenching.bot.chaincapture.UnsafeText
import com.trenching.bot.walletactivity.*
import tools.jackson.databind.JsonNode

internal class WalletTransactionEvidence {
    /**
     * [listingAddress] is the address whose listing selected the transaction, so it must be among its accounts. When
     * that is not the wallet itself, a transaction without the wallet is [WalletHistoryOutcome.WALLET_ABSENT].
     */
    fun classify(node: JsonNode, selection: WalletHistorySelection, wallet: String, listingAddress: String): WalletHistoryOutcome {
        when (CapturedTransactionCheck.shape(node, selection.signature, selection.slot)) {
            CapturedTransactionShape.UNAVAILABLE -> return WalletHistoryOutcome.UNAVAILABLE
            CapturedTransactionShape.UNSUPPORTED_VERSION -> return WalletHistoryOutcome.UNSUPPORTED_VERSION
            CapturedTransactionShape.INVALID -> return WalletHistoryOutcome.INVALID_TRANSACTION
            CapturedTransactionShape.VALID -> Unit
        }
        val accountKeys = node.path("transaction").path("message").path("accountKeys")
        if (accountKeys.none { it.path("pubkey").asString() == listingAddress }) return WalletHistoryOutcome.INVALID_TRANSACTION
        val walletKeys = accountKeys.filter { it.path("pubkey").asString() == wallet }
        if (walletKeys.isEmpty()) return WalletHistoryOutcome.WALLET_ABSENT
        if (walletKeys.size != 1) return WalletHistoryOutcome.INVALID_TRANSACTION
        if (UnsafeText.foundIn(node)) return WalletHistoryOutcome.WITHHELD_UNSAFE_TEXT
        return if (walletKeys.single().path("signer").asBoolean()) WalletHistoryOutcome.UNCLASSIFIED else WalletHistoryOutcome.NON_SIGNER
    }

    /**
     * A transaction fetched for the actual-first-buy verification: it must contain [listingAddress], the address whose listing
     * returned it, and is withheld when it carries unsafe text. Whether it matters is decided offline from its artifact.
     */
    fun classifyVerification(node: JsonNode, signature: String, slot: Long, listingAddress: String): VerificationOutcome {
        when (CapturedTransactionCheck.shape(node, signature, slot)) {
            CapturedTransactionShape.UNAVAILABLE -> return VerificationOutcome.UNAVAILABLE
            CapturedTransactionShape.UNSUPPORTED_VERSION -> return VerificationOutcome.UNSUPPORTED_VERSION
            CapturedTransactionShape.INVALID -> return VerificationOutcome.INVALID_TRANSACTION
            CapturedTransactionShape.VALID -> Unit
        }
        if (accountKeys(node).none { it.path("pubkey").asString() == listingAddress }) return VerificationOutcome.INVALID_TRANSACTION
        return if (UnsafeText.foundIn(node)) VerificationOutcome.WITHHELD_UNSAFE_TEXT else VerificationOutcome.CAPTURED
    }

    /** Whether [wallet] appears exactly once among the transaction's accounts, as a signer. */
    fun signedBy(node: JsonNode, wallet: String): Boolean =
        accountKeys(node).filter { it.path("pubkey").asString() == wallet }.singleOrNull()?.path("signer")?.asBoolean() == true

    private fun accountKeys(node: JsonNode): JsonNode = node.path("transaction").path("message").path("accountKeys")

    /** Whether every token balance of [mint] in the transaction names [tokenProgram] as its program. */
    fun reportsTokenProgram(node: JsonNode, mint: String, tokenProgram: String): Boolean =
        listOf("preTokenBalances", "postTokenBalances").all { field ->
            node.path("meta").path(field).filter { it.path("mint").asString() == mint }
                .all { it.path("programId").let { program -> program.isString && program.asString() == tokenProgram } }
        }
}
