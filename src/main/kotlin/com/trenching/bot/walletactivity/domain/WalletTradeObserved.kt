package com.trenching.bot.walletactivity.domain

import com.trenching.bot.common.*
import java.time.Instant

data class WalletTradeObserved(
    val id: String,
    val wallet: WalletAddress,
    val signature: TransactionSignature,
    val instructionIndex: Int,
    val slot: Slot,
    val blockTime: Instant?,
    val side: TradeSide,
    val pair: MarketPair,
    val baseAmount: TokenAmount,
    val quoteAmount: TokenAmount,
    val source: String,
    val commitment: Commitment,
)
