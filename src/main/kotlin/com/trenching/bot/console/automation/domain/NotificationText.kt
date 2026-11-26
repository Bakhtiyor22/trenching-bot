package com.trenching.bot.console.automation.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** The wording the automation's notifications share: how an amount, an address and a stored code read in a message. */
internal object NotificationText {
    private const val AMOUNT_SCALE = 4
    private const val SHORT_PART_LENGTH = 4
    private const val ELLIPSIS = "…"

    /** [amount] (SOL) with its sign, at four decimals, rounded half up on its size; the sign is the stored value's, so a loss too small to show reads `-0.0000`. Display only. */
    fun signedSol(amount: BigDecimal): String =
        (if (amount.signum() < 0) "-" else "+") + amount.abs().setScale(AMOUNT_SCALE, RoundingMode.HALF_UP).toPlainString() + " SOL"

    /** The first four and last four characters of [address], as the console shows an address. */
    fun short(address: String): String =
        if (address.length <= 2 * SHORT_PART_LENGTH + ELLIPSIS.length) address else address.take(SHORT_PART_LENGTH) + ELLIPSIS + address.takeLast(SHORT_PART_LENGTH)

    /** A stored outcome code in plain words: `TAKE_PROFIT` reads `take profit`. */
    fun words(code: String): String = code.lowercase(Locale.ROOT).replace('_', ' ')
}
