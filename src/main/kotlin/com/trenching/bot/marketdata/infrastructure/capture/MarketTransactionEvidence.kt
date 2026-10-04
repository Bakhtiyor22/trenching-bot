package com.trenching.bot.marketdata.infrastructure.capture

import com.trenching.bot.chaincapture.CapturedTransactionCheck
import com.trenching.bot.chaincapture.CapturedTransactionShape
import com.trenching.bot.chaincapture.UnsafeText
import com.trenching.bot.marketdata.MarketTransactionOutcome
import tools.jackson.databind.JsonNode

/** A market transaction is captured on its listing identity and structure alone; no account has to appear in it. */
internal class MarketTransactionEvidence {
    fun classify(node: JsonNode, signature: String, slot: Long): MarketTransactionOutcome =
        when (CapturedTransactionCheck.shape(node, signature, slot)) {
            CapturedTransactionShape.UNAVAILABLE -> MarketTransactionOutcome.UNAVAILABLE
            CapturedTransactionShape.UNSUPPORTED_VERSION -> MarketTransactionOutcome.UNSUPPORTED_VERSION
            CapturedTransactionShape.INVALID -> MarketTransactionOutcome.INVALID_TRANSACTION
            CapturedTransactionShape.VALID ->
                if (UnsafeText.foundIn(node)) MarketTransactionOutcome.WITHHELD_UNSAFE_TEXT else MarketTransactionOutcome.CAPTURED
        }
}
