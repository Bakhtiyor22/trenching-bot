package com.trenching.bot.console.solana.accounts.infrastructure.helius

import com.trenching.bot.console.solana.accounts.domain.RpcAccountBalance
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Translates the provider's usage answer into a balance, in whole credits (fractions are floored); null when a field the balance needs is
 * missing or unreadable. The credits left are the plan's `creditsRemaining` plus `prepaidCreditsRemaining` (absent: none), floored once.
 * Every number is checked against its bounds before it is rescaled or added, so an absurd exponent is refused, never expanded.
 */
object HeliusUsageMapper {
    private const val UNKNOWN_PLAN = "unknown"
    private const val MAX_PLAN_LENGTH = 32
    private const val MAX_EXPONENT = 64
    private val MAX_CREDITS = BigDecimal.valueOf(Long.MAX_VALUE)

    fun toBalance(response: HeliusUsageResponse): RpcAccountBalance? {
        val planCredits = bounded(response.creditsRemaining) ?: return null
        val prepaidCredits = response.prepaidCreditsRemaining?.let { bounded(it) ?: return null } ?: BigDecimal.ZERO
        val remaining = whole(planCredits.add(prepaidCredits)) ?: return null
        val used = whole(bounded(response.creditsUsed) ?: return null) ?: return null
        val limit = whole(bounded(response.subscriptionDetails?.creditsLimit) ?: return null) ?: return null
        val cycleEnd = date(response.creditCycle?.end) ?: return null
        val plan = response.subscriptionDetails?.plan?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_PLAN_LENGTH) ?: UNKNOWN_PLAN
        return RpcAccountBalance(remaining, used, limit, cycleEnd, plan)
    }

    /** [value] when it is a credit count of a sane size, null when it is absent, negative, beyond a `Long` or written with an absurd exponent. */
    private fun bounded(value: BigDecimal?): BigDecimal? {
        if (value == null || value.scale() !in -MAX_EXPONENT..MAX_EXPONENT) return null
        if (value.signum() < 0 || value.compareTo(MAX_CREDITS) > 0) return null
        return value
    }

    private fun whole(value: BigDecimal): Long? {
        val floored = value.setScale(0, RoundingMode.FLOOR)
        return if (floored.compareTo(MAX_CREDITS) > 0) null else floored.longValueExact()
    }

    private fun date(value: String?): LocalDate? =
        try {
            value?.let(LocalDate::parse)
        } catch (_: DateTimeParseException) {
            null
        }
}
