package com.trenching.bot.protocol.pumpfun

import java.util.HexFormat

/** Pump.fun bonding-curve `buy` and `TradeEvent` byte layout, per the pumpfun-bonding-curve-buy-decoder ADR. */
object PumpFunLayout {
    const val PROGRAM_ID = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"
    const val SOL_AMOUNT_DECIMALS = 9

    val BUY_DISCRIMINATOR: ByteArray = HexFormat.of().parseHex("66063d1201daebea")
    val TRADE_EVENT_PREFIX: ByteArray = HexFormat.of().parseHex("e445a52e51cb9a1d" + "bddb7fd34ee661ee")

    const val U64_BYTES = 8
    const val PUBLIC_KEY_BYTES = 32
    const val BUY_DATA_BYTES = 24
    const val BUY_TOKEN_AMOUNT_OFFSET = 8
    const val EVENT_MINT_OFFSET = 16
    const val EVENT_SOL_AMOUNT_OFFSET = 48
    const val EVENT_TOKEN_AMOUNT_OFFSET = 56
    const val EVENT_IS_BUY_OFFSET = 64
    const val EVENT_USER_OFFSET = 65

    /** Through `creator_fee`; later fields vary between program versions and are not read. */
    const val TRADE_EVENT_PREFIX_BYTES = 233

    /** Base58 of Solana's 10 KiB cross-program instruction data limit fits well within this. */
    const val MAX_DATA_CHARACTERS = 16_384
}
