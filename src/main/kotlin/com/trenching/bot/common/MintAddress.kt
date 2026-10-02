package com.trenching.bot.common

@ConsistentCopyVisibility
data class MintAddress private constructor(val value: String) {
    companion object {
        fun of(value: String): MintAddress {
            Base58.requireBytes(value, 32)
            return MintAddress(value)
        }

        /** Wrapped SOL, the quote asset of SOL-paired swaps. */
        val WRAPPED_SOL: MintAddress = of("So11111111111111111111111111111111111111112")
    }
}
