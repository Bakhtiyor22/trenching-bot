package com.trenching.bot.protocol.pumpfun

import java.time.Instant

/**
 * What public Pump.fun sources establish about which transactions a first-buy verification must see, per the
 * wallet-token capture plan's research findings.
 */
internal object PumpFunCoverage {
    /**
     * From this instant every documented Pump.fun curve buy route passes a user volume accumulator derived from the buyer:
     * the official developer channel's go-live post t.me/pump_tech_updates/16 ("We updated both Pump and PumpSwap programs on
     * Mainnet to require the 2 new additional accounts on buy instructions"), sent 2025-08-01 11:31:28 UTC.
     */
    val ACCUMULATOR_REQUIRED_FROM: Instant = Instant.parse("2025-08-01T11:31:28Z")

    /**
     * `create` and `create_v2`, per pump-fun/pump-public-docs `idl/pump.json`: a mint cannot be bought on its curve before the
     * transaction that runs one of them with the mint as account [PumpFunLayout.CREATE_MINT_ACCOUNT_INDEX].
     */
    val CREATION_DISCRIMINATORS: List<ByteArray> = listOf(PumpFunLayout.CREATE_DISCRIMINATOR, PumpFunLayout.CREATE_V2_DISCRIMINATOR)
}
