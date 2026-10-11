package com.trenching.bot.protocol.solana

/** Program ids of the SPL token programs that own mints and token accounts. */
object SolanaPrograms {
    const val TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    const val ASSOCIATED_TOKEN_ACCOUNT = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"

    /** The programs whose mints have associated token accounts. */
    val TOKEN_PROGRAMS: Set<String> = setOf(TOKEN, TOKEN_2022)
}
