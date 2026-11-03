package com.trenching.bot.console.automation.domain

import com.trenching.bot.common.MintAddress
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A backfill that passed [BackfillRules]; [range] is the unclipped span of its trading days, [filtered] whether its filter narrows the capture filter's. */
data class NewBackfill(
    val requestId: String,
    val fromDay: LocalDate,
    val toDay: LocalDate,
    val range: TimeRange,
    val dryRun: Boolean,
    val requestBudget: Long,
    val filter: BackfillFilter,
    val filtered: Boolean,
)

/**
 * Plan Rule 35 and Key Decisions 9 and 10: `fromDay` not after `toDay`, the last day over, a span of at most [maxDays], a budget
 * from [MIN_REQUEST_BUDGET] (a dry run's) or [captureMinBudget] (a capturing backfill's: the recovery allowance of its first
 * operation and a discovery page, or it could never start one) to [maxRequests], a minimum signal in whole lamports not below [captureMinLamports], and 1 to 50
 * distinct mints. The first rule a request breaks is reported on its field. What depends on stored state (the wallet's coverage,
 * other backfills, `capture-enabled`) is checked where that state is read.
 */
data class BackfillRules(
    val calendar: TradingCalendar,
    val maxDays: Int,
    val maxRequests: Long,
    val captureMinLamports: BigInteger,
    val captureMinBudget: Long,
) {
    fun validate(request: BackfillRequest, now: Instant): NewBackfill {
        val requestId = AutomationRequests.requireRequestId(request.requestId)
        val fromDay = required(FROM_DAY, request.fromDay)
        val toDay = required(TO_DAY, request.toDay)
        val range = range(fromDay, toDay, now)
        val dryRun = required(DRY_RUN, request.dryRun)
        val minimum = if (dryRun) MIN_REQUEST_BUDGET else captureMinBudget
        val filter = BackfillFilter(request.minSignalSol?.let(::minSignal), request.mints?.let(::mints).orEmpty())
        return NewBackfill(requestId, fromDay, toDay, range, dryRun, budget(request.requestBudget, minimum), filter, filter.narrows(captureMinLamports))
    }

    private fun range(fromDay: LocalDate, toDay: LocalDate, now: Instant): TimeRange {
        if (fromDay.isAfter(toDay)) throw InvalidAutomationRequestException(FROM_DAY, "must not be after toDay")
        val range = TimeRange(calendar.range(TradingDay(fromDay)).from, calendar.range(TradingDay(toDay)).to)
        if (range.to.isAfter(now)) throw InvalidAutomationRequestException(TO_DAY, "must be a trading day that has ended")
        if (ChronoUnit.DAYS.between(fromDay, toDay) + 1 > maxDays) {
            throw InvalidAutomationRequestException(TO_DAY, "must be within $maxDays days of fromDay")
        }
        return range
    }

    private fun budget(requestBudget: Long?, minimum: Long): Long {
        val budget = required(REQUEST_BUDGET, requestBudget)
        if (budget !in minimum..maxRequests) {
            throw InvalidAutomationRequestException(REQUEST_BUDGET, "must be between $minimum and $maxRequests")
        }
        return budget
    }

    private fun minSignal(minSignalSol: BigDecimal): BigInteger {
        if (minSignalSol.signum() <= 0) throw InvalidAutomationRequestException(MIN_SIGNAL_SOL, "must be positive")
        if (minSignalSol.stripTrailingZeros().scale() > PumpFunLayout.SOL_AMOUNT_DECIMALS) {
            throw InvalidAutomationRequestException(MIN_SIGNAL_SOL, "must be a whole number of lamports")
        }
        val lamports = minSignalSol.movePointRight(PumpFunLayout.SOL_AMOUNT_DECIMALS).toBigIntegerExact()
        if (lamports < captureMinLamports) {
            throw InvalidAutomationRequestException(MIN_SIGNAL_SOL, "must be at least the capture minimum of $captureMinLamports lamports")
        }
        return lamports
    }

    private fun mints(mints: List<String>): List<String> {
        if (mints.size !in 1..MAX_MINTS) throw InvalidAutomationRequestException(MINTS, "must hold 1 to $MAX_MINTS mints")
        if (mints.toSet().size != mints.size) throw InvalidAutomationRequestException(MINTS, "must not repeat a mint")
        mints.forEach { mint ->
            try {
                MintAddress.of(mint)
            } catch (_: IllegalArgumentException) {
                throw InvalidAutomationRequestException(MINTS, "must be base58 mint addresses")
            }
        }
        return mints
    }

    private fun <T : Any> required(field: String, value: T?): T = value ?: throw InvalidAutomationRequestException(field, "is required")

    companion object {
        const val MIN_REQUEST_BUDGET = 100L
        const val MAX_MINTS = 50

        private const val FROM_DAY = "fromDay"
        private const val TO_DAY = "toDay"
        private const val DRY_RUN = "dryRun"
        private const val REQUEST_BUDGET = "requestBudget"
        private const val MIN_SIGNAL_SOL = "minSignalSol"
        private const val MINTS = "mints"
    }
}
