package com.trenching.bot.protocol.pumpfun

import java.util.HexFormat

/** Pump.fun bonding-curve `buy` and `TradeEvent` byte layout, per the pumpfun-bonding-curve-buy-decoder ADR. */
object PumpFunLayout {
    const val PROGRAM_ID = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"
    const val SOL_AMOUNT_DECIMALS = 9

    /** Every bonding-curve token is minted once with this supply and these decimals. */
    const val TOKEN_SUPPLY = 1_000_000_000L
    const val TOKEN_DECIMALS = 6

    /** Source of a wallet buy decoded from a bonding-curve `buy`, per the pumpfun-bonding-curve-buy-decoder ADR. */
    const val BUY_SOURCE = "pumpfun-bonding-curve-buy/v1"

    /** Seed of the per-user volume accumulator PDA that `buy` passes, with the user's public key as second seed. */
    const val USER_VOLUME_ACCUMULATOR_SEED = "user_volume_accumulator"

    val BUY_DISCRIMINATOR: ByteArray = HexFormat.of().parseHex("66063d1201daebea")

    /** Anchor `global:buy_exact_quote_in_v3`: spendable quote in u64, minimum base out u64, then bytes this decoder ignores. */
    val BUY_EXACT_QUOTE_IN_V3_DISCRIMINATOR: ByteArray = HexFormat.of().parseHex("e1f7501ed5b38488")
    const val BUY_EXACT_QUOTE_IN_V3_DATA_BYTES = 24
    const val BUY_EXACT_QUOTE_IN_V3_SPENDABLE_OFFSET = 8
    const val BUY_EXACT_QUOTE_IN_V3_MIN_OUT_OFFSET = 16

    /**
     * Anchor `global:create` and `global:create_v2`, the only instructions that create a coin, per every published
     * pump-fun/pump-public-docs `idl/pump.json`. In both, account [CREATE_MINT_ACCOUNT_INDEX] is the mint they initialise.
     */
    val CREATE_DISCRIMINATOR: ByteArray = HexFormat.of().parseHex("181ec828051c0777")
    val CREATE_V2_DISCRIMINATOR: ByteArray = HexFormat.of().parseHex("d6904cec5f8b31b4")
    const val CREATE_MINT_ACCOUNT_INDEX = 0

    /** Recognised Pump.fun instructions that no decoder supports, by Anchor discriminator; named only for capture summaries. */
    val UNSUPPORTED_INSTRUCTIONS: Map<String, PumpFunInstructionName> = mapOf(
        "33e685a4017f83ad" to PumpFunInstructionName.SELL,
        "1c92de7726c469d5" to PumpFunInstructionName.SELL_V3,
        "c62e1552b4d9e870" to PumpFunInstructionName.BUY_EXACT_QUOTE_IN,
        "07051dc4f5176550" to PumpFunInstructionName.BUY_V3,
    )
    const val DISCRIMINATOR_BYTES = 8
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

    /** After user (32) and timestamp (8). */
    const val EVENT_VIRTUAL_SOL_RESERVES_OFFSET = 105
    const val EVENT_VIRTUAL_TOKEN_RESERVES_OFFSET = 113
    const val EVENT_REAL_SOL_RESERVES_OFFSET = 121
    const val EVENT_REAL_TOKEN_RESERVES_OFFSET = 129

    /** After fee_recipient (32). */
    const val EVENT_FEE_BASIS_POINTS_OFFSET = 169
    const val EVENT_FEE_OFFSET = 177

    /** After creator (32). */
    const val EVENT_CREATOR_FEE_BASIS_POINTS_OFFSET = 217
    const val EVENT_CREATOR_FEE_OFFSET = 225

    /** Through `creator_fee`; later fields vary between program versions and are not read. */
    const val TRADE_EVENT_PREFIX_BYTES = 233

    /** Base58 of Solana's 10 KiB cross-program instruction data limit fits well within this. */
    const val MAX_DATA_CHARACTERS = 16_384
}
