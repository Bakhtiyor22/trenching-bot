package com.trenching.bot.walletactivity.domain

import com.trenching.bot.common.*
import java.time.Instant

data class WalletTransaction(
    val wallet: WalletAddress,
    val signature: TransactionSignature,
    val instructionIndex: Int,
    val slot: Slot,
    val blockTime: Instant?,
    val pair: MarketPair,
    val baseDelta: java.math.BigInteger,
    val quoteDelta: java.math.BigInteger,
    val baseDecimals: Int,
    val quoteDecimals: Int,
    val successful: Boolean,
    val supportedRoute: Boolean,
    val signer: Boolean,
    val source: String,
    val commitment: Commitment,
)
