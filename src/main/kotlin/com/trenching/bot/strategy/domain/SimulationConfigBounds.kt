package com.trenching.bot.strategy.domain

import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import java.math.BigDecimal

object SimulationConfigBounds {
    /** The dip below P0, the take-profit gain and the stop-loss drop: each a real move, below 100%. */
    val LEVEL_BASIS_POINTS: IntRange = 1..9999
    val MAX_AGE_SLOTS: LongRange = 0L..1000L

    /** About 30 minutes at 400 ms slots; a market capture records no further. */
    const val MAX_HORIZON_SLOTS = 4500L
    val HORIZON_SLOTS: LongRange = 1L..MAX_HORIZON_SLOTS
    val MAX_MIN_MARKET_CAP_USD: BigDecimal = BigDecimal("1000000000")

    /** A notional is spent as whole lamports. */
    const val NOTIONAL_DECIMALS = PumpFunLayout.SOL_AMOUNT_DECIMALS

    /** About a minute at 400 ms slots. */
    val LATENCY_SLOTS: LongRange = 1L..150L
    val MAX_TRANSACTION_COST_SOL: BigDecimal = BigDecimal("0.1")

    /** Spec P7's dip window, from the wallet buy's block time: one second to ten minutes. */
    val DIP_WINDOW_MS: LongRange = 1_000L..600_000L

    /** Spec P11's maximum hold and hard exit start: one second to an hour each. */
    val EXIT_CLOCK_MS: LongRange = 1_000L..3_600_000L
}
