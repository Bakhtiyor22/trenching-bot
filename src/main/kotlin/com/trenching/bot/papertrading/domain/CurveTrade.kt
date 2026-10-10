package com.trenching.bot.papertrading.domain

import java.math.BigDecimal
import java.math.BigInteger

/**
 * One paper buy or sell against a bonding curve: [lamports] paid in or received out for [rawTokens], before fees.
 * [feeLamports] is the protocol plus creator fee at [feeBps], and [priceImpactBps] compares [executionPrice] with the
 * curve's spot price before the trade (positive for a buy, negative for a sell).
 */
data class CurveTrade(
    val lamports: BigInteger,
    val rawTokens: BigInteger,
    val feeLamports: BigInteger,
    val feeBps: Int,
    val executionPrice: BigDecimal,
    val priceImpactBps: Int,
)
