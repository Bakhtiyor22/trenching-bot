package com.trenching.bot.walletactivity.infrastructure.capture

import com.trenching.bot.chaincapture.CapturedTransactionCheck
import com.trenching.bot.chaincapture.CapturedTransactionShape
import com.trenching.bot.chaincapture.UnsafeText
import com.trenching.bot.walletactivity.*
import tools.jackson.databind.JsonNode

internal class WalletTransactionEvidence {
    fun classify(node: JsonNode, selection: WalletHistorySelection, wallet: String): WalletHistoryOutcome {
        when (CapturedTransactionCheck.shape(node, selection.signature, selection.slot)) {
            CapturedTransactionShape.UNAVAILABLE -> return WalletHistoryOutcome.UNAVAILABLE
            CapturedTransactionShape.UNSUPPORTED_VERSION -> return WalletHistoryOutcome.UNSUPPORTED_VERSION
            CapturedTransactionShape.INVALID -> return WalletHistoryOutcome.INVALID_TRANSACTION
            CapturedTransactionShape.VALID -> Unit
        }
        val walletKeys = node.path("transaction").path("message").path("accountKeys").filter { it.path("pubkey").asString() == wallet }
        if (walletKeys.size != 1) return WalletHistoryOutcome.INVALID_TRANSACTION
        if (UnsafeText.foundIn(node)) return WalletHistoryOutcome.WITHHELD_UNSAFE_TEXT
        return if (walletKeys.single().path("signer").asBoolean()) WalletHistoryOutcome.UNCLASSIFIED else WalletHistoryOutcome.NON_SIGNER
    }
}
