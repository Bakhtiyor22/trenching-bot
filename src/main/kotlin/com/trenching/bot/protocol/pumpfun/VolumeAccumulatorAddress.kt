package com.trenching.bot.protocol.pumpfun

import com.trenching.bot.common.Base58
import com.trenching.bot.protocol.solana.ProgramAddress

/**
 * A buyer's user volume accumulators, PDA(["user_volume_accumulator", user]) under each program, derived offline. Pump.fun
 * curve buys pass the Pump program's; a PumpSwap multi-hop curve hop passes PumpSwap's instead.
 */
object VolumeAccumulatorAddress {
    /** Null only if every bump lands on the curve. */
    fun pump(user: String): String? = derive(user, PumpFunLayout.PROGRAM_ID)

    /** Null only if every bump lands on the curve. */
    fun pumpSwap(user: String): String? = derive(user, PumpSwapLayout.PROGRAM_ID)

    private fun derive(user: String, programId: String): String? {
        Base58.requireBytes(user, ProgramAddress.PUBLIC_KEY_BYTES)
        val seeds = listOf(PumpFunLayout.USER_VOLUME_ACCUMULATOR_SEED.toByteArray(Charsets.US_ASCII), requireNotNull(Base58.decode(user)))
        return ProgramAddress.find(seeds, programId)?.address
    }
}
