package com.trenching.bot.marketdata.domain

import java.math.BigDecimal

sealed interface ValuationOutcome {
    /** The total-supply valuation in USDT at the decimal policy's scale. */
    data class Valued(val usdt: BigDecimal) : ValuationOutcome

    data class Unavailable(val reason: ValuationUnavailableReason) : ValuationOutcome
}

enum class ValuationUnavailableReason {
    /** The observation is not from the Pump.fun trade source, whose tokens have the fixed bonding-curve supply. */
    UNSUPPORTED_TOKEN,
    NOT_SOL_QUOTED,

    /** The trade's amounts do not give the observation's price under the protocol's six token decimals. */
    DECIMALS_MISMATCH,
}
