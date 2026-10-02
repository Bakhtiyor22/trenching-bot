package com.trenching.bot.common

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

object ChainValueMapper {
    @JvmStatic fun wallet(value: String): WalletAddress = WalletAddress.of(value)
    @JvmStatic fun signature(value: String): TransactionSignature = TransactionSignature.of(value)
    @JvmStatic fun slot(value: Long): Slot = Slot.of(value)
    @JvmStatic fun pair(base: String, quote: String): MarketPair = MarketPair.of(MintAddress.of(base), MintAddress.of(quote))
    @JvmStatic fun amount(raw: BigInteger, decimals: Int): TokenAmount = TokenAmount.of(raw.abs(), decimals)
    @JvmStatic fun originalDecimal(value: BigDecimal, scale: Int): BigDecimal = value.setScale(scale, RoundingMode.UNNECESSARY)
}
