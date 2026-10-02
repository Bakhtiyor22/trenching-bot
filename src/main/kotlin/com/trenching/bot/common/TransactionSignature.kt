package com.trenching.bot.common

@ConsistentCopyVisibility
data class TransactionSignature private constructor(val value: String) {
    companion object {
        fun of(value: String): TransactionSignature {
            Base58.requireBytes(value, 64)
            return TransactionSignature(value)
        }
    }
}
