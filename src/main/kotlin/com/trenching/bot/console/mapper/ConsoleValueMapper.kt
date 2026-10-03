package com.trenching.bot.console.mapper

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Value conversions for API responses. Decimals and raw amounts become exact strings: `toPlainString`
 * because `toString` would print a scale-12 zero as `0E-12`.
 */
object ConsoleValueMapper {
    @JvmStatic fun decimal(value: BigDecimal?): String? = value?.toPlainString()
    @JvmStatic fun rawAmount(value: BigInteger?): String? = value?.toString()
    @JvmStatic fun slot(value: Slot?): Long? = value?.value
    @JvmStatic fun mint(value: MintAddress?): String? = value?.value
    @JvmStatic fun wallet(value: WalletAddress?): String? = value?.value
    @JvmStatic fun signature(value: TransactionSignature?): String? = value?.value
}
