package com.trenching.bot.protocol.solana

import com.trenching.bot.common.Base58

/** Token account addresses, derived offline. */
object TokenAccountAddress {
    /**
     * The associated token account PDA([owner, tokenProgram, mint], associated token account program). It is a pure function of
     * its seeds, so it names the same account after it is closed and reopened. Null only if every bump lands on the curve.
     */
    fun associated(owner: String, tokenProgram: String, mint: String): String? {
        val seeds = listOf(owner, tokenProgram, mint).map { key ->
            Base58.requireBytes(key, ProgramAddress.PUBLIC_KEY_BYTES)
            requireNotNull(Base58.decode(key))
        }
        return ProgramAddress.find(seeds, SolanaPrograms.ASSOCIATED_TOKEN_ACCOUNT)?.address
    }
}
