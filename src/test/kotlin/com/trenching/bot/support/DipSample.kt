package com.trenching.bot.support

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import tools.jackson.databind.json.JsonMapper

/**
 * The bundled synthetic dip-entry dataset (`dip-sample-v3`): one mint per cycle outcome, with values hand-checked from
 * the engine's scales and rounding. Every buy has P0 = 1e-7 SOL per token except [LOW_CAP] (1e-8); the rate is 200
 * USDT per SOL in the first minute and 150 after slot 5000.
 * The P&L values are for the `SimulationConfig.mvp()` defaults (0.1 SOL notional, latency 1, 0.001 SOL per transaction),
 * recomputed by hand from the sample's curves: integer curve math rounding down, fees of 95 + 30 bps rounding up.
 */
object DipSample {
    const val RESOURCE = "/replay/samples/dip-sample-v3.json"
    const val TAKE_PROFIT = 0
    const val STOP_LOSS = 1
    const val NO_DIP = 2
    const val LOW_CAP = 3
    const val MIGRATED_EARLY = 4
    const val MIGRATED_IN_POSITION = 5
    const val HORIZON = 6
    const val NO_EXIT_FILL = 7
    const val NO_FILL = 8
    const val UNSUPPORTED_SUPPLY = 9

    /**
     * The take-profit cycle: dip at slot 1002; 100000000 lamports buy 1160584892249 raw tokens from slot 1003's curve (impact
     * 19 bps, fees 1250000 lamports, cost basis 0.10225); TP trigger 1005; the sale into slot 1006's curve returns 133175124
     * lamports less 1664690 in fees and the 0.001 transaction cost.
     */
    const val TAKE_PROFIT_PNL = "0.028260434000"

    /**
     * The stop-loss cycle: 1247541616895 raw tokens bought from slot 2002's curve (cost basis 0.10225); trigger at 0.00000006
     * in slot 2004, then a gap sale into slot 2005's curve for 62280246 lamports less 778504 in fees and the transaction cost.
     */
    const val STOP_LOSS_PNL = "-0.041748258000"
    const val TOTAL_PNL = "-0.013487824000"

    fun mint(cycle: Int): MintAddress = MintAddress.of("1".repeat(31) + "23456789AB"[cycle])

    fun pair(cycle: Int): MarketPair = MarketPair.of(mint(cycle), MintAddress.WRAPPED_SOL)

    fun json(): String = requireNotNull(javaClass.getResource(RESOURCE)).readText()

    fun load(): ReplayDataset = ReplayDatasetLoader(JsonMapper.builder().build()).load(json().byteInputStream())
}
