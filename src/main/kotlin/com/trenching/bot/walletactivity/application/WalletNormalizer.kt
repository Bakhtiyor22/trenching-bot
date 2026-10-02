package com.trenching.bot.walletactivity.application

import com.trenching.bot.common.*
import com.trenching.bot.walletactivity.domain.*

class WalletNormalizer {
    /** Only swaps against [quoteMint] become trades, so every scenario's notional and P&L share one quote unit. */
    fun normalize(transaction: WalletTransaction, trackedWallet: WalletAddress, quoteMint: MintAddress): NormalizationOutcome {
        require(transaction.instructionIndex >= 0) { "Negative instruction index" }
        require(transaction.source.isNotBlank()) { "Missing transaction source" }
        val id = WalletEventIdentity.of(transaction.signature, transaction.instructionIndex)
        val ignored = when {
            !transaction.successful -> IngestionReason.FAILED
            transaction.wallet != trackedWallet -> IngestionReason.OTHER_WALLET
            !transaction.signer -> IngestionReason.NOT_SIGNER
            transaction.commitment == Commitment.PROCESSED -> IngestionReason.NOT_CONFIRMED
            !transaction.supportedRoute -> IngestionReason.UNSUPPORTED_ROUTE
            transaction.pair.quote != quoteMint -> IngestionReason.UNSUPPORTED_QUOTE
            transaction.baseDelta.signum() * transaction.quoteDelta.signum() != -1 -> IngestionReason.INVALID_DELTAS
            else -> null
        }
        if (ignored != null) return NormalizationOutcome.Ignored(id, ignored)
        val side = if (transaction.baseDelta.signum() > 0) TradeSide.BUY else TradeSide.SELL
        return NormalizationOutcome.Accepted(WalletTradeObserved(
            id, transaction.wallet, transaction.signature, transaction.instructionIndex,
            transaction.slot, transaction.blockTime, side, transaction.pair,
            TokenAmount.of(transaction.baseDelta.abs(), transaction.baseDecimals),
            TokenAmount.of(transaction.quoteDelta.abs(), transaction.quoteDecimals),
            transaction.source, transaction.commitment,
        ))
    }
}
