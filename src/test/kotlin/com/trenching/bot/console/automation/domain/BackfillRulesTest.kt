package com.trenching.bot.console.automation.domain

import com.trenching.bot.common.Base58
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/** Plan Rule 35 and Key Decisions 9 and 10: what a backfill request must satisfy, and the range it converts to. */
class BackfillRulesTest {
    private val zone = ZoneOffset.ofHours(5)
    private val rules = BackfillRules(TradingCalendar(LocalTime.NOON, zone), maxDays = 31, maxRequests = 20_000, captureMinLamports = BigInteger.valueOf(1_500_000_000),
        captureMinBudget = 1222)

    /** 14:00 local on 2 November 2026: the trading day of 1 November has ended, that of 2 November has not. */
    private val now = Instant.parse("2026-11-02T09:00:00Z")

    private val valid = BackfillRequest(
        requestId = "req-1", fromDay = LocalDate.parse("2026-10-30"), toDay = LocalDate.parse("2026-11-01"), dryRun = false, requestBudget = 5000,
        minSignalSol = null, mints = null,
    )

    @Test
    fun `a day range becomes from the first day's start to the day after the last day's start, in the trading zone`() {
        val backfill = rules.validate(valid, now)

        assertEquals(Instant.parse("2026-10-30T07:00:00Z"), backfill.range.from)
        assertEquals(Instant.parse("2026-11-02T07:00:00Z"), backfill.range.to)
        assertEquals(5000, backfill.requestBudget)
        assertEquals(BackfillFilter(null, emptyList()), backfill.filter)
    }

    @Test
    fun `a day that has not ended yet is refused on toDay`() {
        assertField("toDay", valid.copy(toDay = LocalDate.parse("2026-11-02")))
    }

    @Test
    fun `the day that ended exactly now is allowed`() {
        val backfill = rules.validate(valid.copy(toDay = LocalDate.parse("2026-11-01")), Instant.parse("2026-11-02T07:00:00Z"))

        assertEquals(Instant.parse("2026-11-02T07:00:00Z"), backfill.range.to)
    }

    @Test
    fun `fromDay after toDay is refused on fromDay`() {
        assertField("fromDay", valid.copy(fromDay = LocalDate.parse("2026-11-01"), toDay = LocalDate.parse("2026-10-31")))
    }

    @Test
    fun `a span of the maximum is allowed and one day more is refused on toDay`() {
        val last = LocalDate.parse("2026-11-01")

        rules.validate(valid.copy(fromDay = last.minusDays(30), toDay = last), now)

        assertField("toDay", valid.copy(fromDay = last.minusDays(31), toDay = last))
    }

    @Test
    fun `the request budget is required and within the minimum and the maximum`() {
        assertField("requestBudget", valid.copy(requestBudget = null))
        assertField("requestBudget", valid.copy(requestBudget = 99, dryRun = true))
        assertField("requestBudget", valid.copy(requestBudget = 20_001))
        assertEquals(1222, rules.validate(valid.copy(requestBudget = 1222), now).requestBudget)
        assertEquals(20_000, rules.validate(valid.copy(requestBudget = 20_000), now).requestBudget)
    }

    @Test
    fun `a capturing backfill's budget must pay for one operation's recovery allowance and a discovery page, a dry run's only 100`() {
        assertField("requestBudget", valid.copy(requestBudget = 1221))
        assertField("requestBudget", valid.copy(requestBudget = 100))
        assertEquals(100, rules.validate(valid.copy(requestBudget = 100, dryRun = true), now).requestBudget)
    }

    @Test
    fun `the minimum signal is positive whole lamports and at least the capture minimum`() {
        assertField("minSignalSol", valid.copy(minSignalSol = BigDecimal("1.499999999")))
        assertField("minSignalSol", valid.copy(minSignalSol = BigDecimal.ZERO))
        assertField("minSignalSol", valid.copy(minSignalSol = BigDecimal("-2")))
        assertField("minSignalSol", valid.copy(minSignalSol = BigDecimal("2.0000000001")))
        assertEquals(BigInteger.valueOf(1_500_000_000), rules.validate(valid.copy(minSignalSol = BigDecimal("1.5")), now).filter.minSignalLamports)
        assertEquals(BigInteger.valueOf(2_000_000_001), rules.validate(valid.copy(minSignalSol = BigDecimal("2.000000001")), now).filter.minSignalLamports)
    }

    @Test
    fun `a backfill with a token list or a minimum above the capture minimum is filtered, one without or at it is not`() {
        assertEquals(false, rules.validate(valid, now).filtered)
        assertEquals(false, rules.validate(valid.copy(minSignalSol = BigDecimal("1.5")), now).filtered)
        assertEquals(true, rules.validate(valid.copy(minSignalSol = BigDecimal("1.500000001")), now).filtered)
        assertEquals(true, rules.validate(valid.copy(mints = listOf(mint(1))), now).filtered)
    }

    @Test
    fun `without a minimum signal the filter has none, so the capture filter applies`() {
        assertNull(rules.validate(valid, now).filter.minSignalLamports)
    }

    @Test
    fun `the token list holds one to fifty distinct valid mints`() {
        val mints = (1..51).map(::mint)

        assertField("mints", valid.copy(mints = emptyList()))
        assertField("mints", valid.copy(mints = mints))
        assertField("mints", valid.copy(mints = listOf(mints[0], mints[0])))
        assertField("mints", valid.copy(mints = listOf(mints[0], "not-a-mint")))
        assertEquals(mints.take(50), rules.validate(valid.copy(mints = mints.take(50)), now).filter.mints)
    }

    @Test
    fun `a missing field is refused on that field`() {
        assertField("requestId", valid.copy(requestId = null))
        assertField("requestId", valid.copy(requestId = "not valid!"))
        assertField("fromDay", valid.copy(fromDay = null))
        assertField("toDay", valid.copy(toDay = null))
        assertField("dryRun", valid.copy(dryRun = null))
    }

    private fun assertField(field: String, request: BackfillRequest) {
        val failure = assertFailsWith<InvalidAutomationRequestException>("expected a refusal on $field") { rules.validate(request, now) }
        assertEquals(field, failure.field)
    }

    private fun mint(n: Int): String = Base58.encode(ByteArray(32) { (n + 40).toByte() })
}
