package com.trenching.bot.console.solana

enum class SolanaCluster(val label: String, private val genesisHash: String?) {
    DEVNET("devnet", "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG"),
    TESTNET("testnet", "4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY"),
    MAINNET_BETA("mainnet-beta", "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"),
    UNKNOWN("unknown", null);

    companion object {
        fun fromGenesisHash(genesisHash: String): SolanaCluster =
            entries.firstOrNull { it.genesisHash == genesisHash } ?: UNKNOWN
    }
}
