package com.trenching.bot.replay.infrastructure.fixture

/** Property names of an observation's `curve` object in replay dataset v3. */
internal object ReplayCurveFields {
    const val VIRTUAL_SOL_RESERVES = "virtualSolReserves"
    const val VIRTUAL_TOKEN_RESERVES = "virtualTokenReserves"
    const val REAL_SOL_RESERVES = "realSolReserves"
    const val REAL_TOKEN_RESERVES = "realTokenReserves"
    const val FEE_BPS = "feeBps"
    const val CREATOR_FEE_BPS = "creatorFeeBps"
}
