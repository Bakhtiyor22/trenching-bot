package com.trenching.bot.common

@ConsistentCopyVisibility
data class WalletAddress private constructor(val value: String) {
    companion object {
        fun of(value: String): WalletAddress {
            Base58.requireBytes(value, 32)
            return WalletAddress(value)
        }
    }
}
