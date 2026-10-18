package com.trenching.bot.console.application

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress

/** Parses user-supplied addresses and signatures; their `of` factories only offer a throwing check. */
object ConsoleAddresses {
    fun mintOrNull(value: String): MintAddress? = try {
        MintAddress.of(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun walletOrNull(value: String): WalletAddress? = try {
        WalletAddress.of(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun signatureOrNull(value: String): TransactionSignature? = try {
        TransactionSignature.of(value)
    } catch (_: IllegalArgumentException) {
        null
    }
}
