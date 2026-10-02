package com.trenching.bot.common

@ConsistentCopyVisibility
data class MarketPair private constructor(val base: MintAddress, val quote: MintAddress) {
    companion object {
        fun of(base: MintAddress, quote: MintAddress): MarketPair {
            require(base != quote) { "Base and quote mint must differ" }
            return MarketPair(base, quote)
        }
    }
}
