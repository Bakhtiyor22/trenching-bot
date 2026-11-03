package com.trenching.bot.console.automation.domain

import java.math.BigInteger

/**
 * Plan Key Decision 9: what narrows a backfill. [minSignalLamports] overrides the capture filter's minimum (never below it, which
 * [BackfillRules] checks); [mints] limits the buys that become items to those mints, or to every mint when empty.
 */
data class BackfillFilter(val minSignalLamports: BigInteger?, val mints: List<String>) {
    private val allowed: Set<String> = mints.toSet()

    /** The capture filter the backfill applies to a buy's size: [default] with the minimum replaced when this filter has one. */
    fun captureFilter(default: CaptureFilter): CaptureFilter = minSignalLamports?.let { default.copy(minSignalLamports = it) } ?: default

    fun allowsMint(mint: String): Boolean = allowed.isEmpty() || mint in allowed

    /**
     * Whether this filter leaves out buys the capture filter alone ([captureMinLamports]) would take: a token list, or a minimum
     * above the capture minimum. Such a backfill's range is not coverage, since it did not find every buy the sweep can use.
     */
    fun narrows(captureMinLamports: BigInteger): Boolean = mints.isNotEmpty() || (minSignalLamports?.let { it > captureMinLamports } == true)
}
