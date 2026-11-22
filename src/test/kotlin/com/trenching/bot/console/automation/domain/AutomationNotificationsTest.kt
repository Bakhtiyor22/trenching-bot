package com.trenching.bot.console.automation.domain

import com.trenching.bot.notification.NotificationKind
import com.trenching.bot.notification.NotificationLink
import com.trenching.bot.portfolio.domain.PositionState
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The two messages the automation publishes (notification plan Rules 3 to 6), built from stored rows alone. */
class AutomationNotificationsTest {
    private val calendar = TradingCalendar(LocalTime.NOON, ZoneOffset.ofHours(5))
    private val buyMessage = BuyResultMessage(calendar)

    /** 21:07 on 2 November 2026 in UTC+5, which is the trading day of 2 November. */
    private val boughtAt = Instant.parse("2026-11-02T16:07:00Z")
    private val mint = "So11111111111111111111111111111111111111112"
    private val buy = BuySubject("item-1", mint, boughtAt, null)

    @Test
    fun `a buy with a profit and a loss lists each by its sign with the amount at four decimals and its outcome in words`() {
        val message = assertNotNull(buyMessage.of(buy, listOf(
            closed(0, "0.041234567890", "TAKE_PROFIT", "run-0"),
            closed(3, "-0.021000000000", "STOP_LOSS", "run-3"),
        )))

        assertEquals("BUY_RESULT|item-1", message.key)
        assertEquals(NotificationKind.BUY_RESULT, message.kind)
        assertEquals("Buy So11…1112 at 2 Nov 21:07: 1 profit, 1 loss", message.title)
        assertEquals(listOf("V00 profit +0.0412 SOL, take profit", "V03 loss -0.0210 SOL, stop loss"), message.lines)
    }

    @Test
    fun `the other variations are listed once by label under no trade with their outcome, in seq order`() {
        val message = assertNotNull(buyMessage.of(buy, listOf(
            idle(4, "HORIZON_REACHED", "run-4", PositionState.OPEN),
            closed(1, "0.010000000000", "TAKE_PROFIT", "run-1"),
            idle(2, "NO_DIP", "run-2", null),
            closed(5, "0.000000000000", "TIME_EXIT", "run-5"),
            refused(3, "MARKET_COVERAGE_ENDED"),
        )))

        assertEquals(
            listOf("V01 profit +0.0100 SOL, take profit", "No trade: V02 no dip, V03 market coverage ended, V04 horizon reached, V05 time exit"),
            message.lines,
        )
    }

    @Test
    fun `variations come out in seq order even when their labels sort differently`() {
        val message = assertNotNull(buyMessage.of(buy, listOf(
            closed(100, "0.020000000000", "TAKE_PROFIT", "run-100").copy(label = "V100"),
            closed(11, "0.010000000000", "TAKE_PROFIT", "run-11"),
        )))

        assertEquals(listOf("V11 profit +0.0100 SOL, take profit", "V100 profit +0.0200 SOL, take profit"), message.lines)
        assertEquals(listOf("Buy", "V11", "V100"), message.links.map { it.label })
    }

    @Test
    fun `the links are the buy's page in the console, then a run page for each variation that has a run, by seq`() {
        val message = assertNotNull(buyMessage.of(buy, listOf(
            refused(5, "DATASET_NOT_FOUND"),
            closed(2, "0.010000000000", "TAKE_PROFIT", "run-2"),
            idle(1, "NO_DIP", "run-1", null),
        )))

        assertEquals(
            listOf(
                NotificationLink("Buy", "/automation/days/2026-11-02?item=item-1"),
                NotificationLink("V01", "/runs/run-1"),
                NotificationLink("V02", "/runs/run-2"),
            ),
            message.links,
        )
    }

    @Test
    fun `the buy's page is under the trading day of the automation's calendar, not the calendar date`() {
        val beforeNoon = Instant.parse("2026-11-03T05:00:00Z")

        val message = assertNotNull(buyMessage.of(buy.copy(boughtAt = beforeNoon), listOf(closed(0, "0.010000000000", "TAKE_PROFIT", "run-0"))))

        assertEquals("/automation/days/2026-11-02?item=item-1", message.links.first().path)
        assertEquals("Buy So11…1112 at 3 Nov 10:00: 1 profit, 0 loss", message.title)
    }

    @Test
    fun `an amount rounds half up at the fifth decimal on its size, and keeps the sign of the stored value`() {
        val lines = assertNotNull(buyMessage.of(buy, listOf(
            closed(0, "0.000050000000", "TAKE_PROFIT", "run-0"),
            closed(1, "0.000049999999", "TAKE_PROFIT", "run-1"),
            closed(2, "-0.000050000000", "STOP_LOSS", "run-2"),
            closed(3, "-0.000010000000", "STOP_LOSS", "run-3"),
        ))).lines

        assertEquals(
            listOf("V00 profit +0.0001 SOL, take profit", "V01 profit +0.0000 SOL, take profit", "V02 loss -0.0001 SOL, stop loss", "V03 loss -0.0000 SOL, stop loss"),
            lines,
        )
    }

    @Test
    fun `a buy where no position closed with a profit or a loss has no message`() {
        val results = listOf(
            closed(0, "0.000000000000", "TIME_EXIT", "run-0"),
            idle(1, "HORIZON_REACHED", "run-1", PositionState.OPEN),
            idle(2, "NO_DIP", "run-2", null),
            refused(3, "MARKET_COVERAGE_ENDED"),
        )

        assertNull(buyMessage.of(buy, results))
        assertNull(buyMessage.of(buy, emptyList()))
    }

    @Test
    fun `an open position with a stored profit is not a closed one`() {
        assertNull(buyMessage.of(buy, listOf(idle(0, "HORIZON_REACHED", "run-0", PositionState.OPEN).copy(realizedPnl = BigDecimal("0.5")))))
    }

    @Test
    fun `a backfilled buy has no message even with a closed profit`() {
        assertNull(buyMessage.of(buy.copy(backfillId = "backfill-1"), listOf(closed(0, "0.010000000000", "TAKE_PROFIT", "run-0"))))
    }

    @Test
    fun `a day is named with how many variations succeeded, each success with its worst case and signals, and the other standings counted`() {
        val message = assertNotNull(DayVerdictMessage.of(DAY, listOf(
            verdict("V01", EvaluationStatus.NO_SUCCESS),
            verdict("V02", EvaluationStatus.SUCCESS, worstCase = "0.012300000000", signals = 12),
            verdict("V03", EvaluationStatus.NO_RESULT),
            verdict("V04", EvaluationStatus.SUCCESS, worstCase = "0.051000000000", signals = 15),
            verdict("V05", EvaluationStatus.NO_SUCCESS),
        )))

        assertEquals("DAY_VERDICT|decu-sweep-1|2026-11-05", message.key)
        assertEquals(NotificationKind.DAY_VERDICT, message.kind)
        assertEquals("Day 2026-11-05: 2 of 5 variations SUCCESS", message.title)
        assertEquals(
            listOf("V04 SUCCESS: worst case +0.0510 SOL over 15 signals", "V02 SUCCESS: worst case +0.0123 SOL over 12 signals", "Others: 2 NO_SUCCESS, 1 NO_RESULT"),
            message.lines,
        )
        assertEquals(listOf(NotificationLink("Day", "/automation/days/2026-11-05")), message.links)
    }

    @Test
    fun `a decided day with no success lists only the counts of the other standings`() {
        val message = assertNotNull(DayVerdictMessage.of(DAY, listOf(verdict("V00", EvaluationStatus.NO_RESULT), verdict("V01", EvaluationStatus.NO_RESULT))))

        assertEquals("Day 2026-11-05: 0 of 2 variations SUCCESS", message.title)
        assertEquals(listOf("Others: 2 NO_RESULT"), message.lines)
    }

    @Test
    fun `a day with one pending row, or with no row at all, has no message`() {
        val pending = DayVerdict(DAY, PLAN, "V03", listOf(DAY), tally(), DayStanding.Pending(TradingDay(DAY.minusDays(1))))

        assertNull(DayVerdictMessage.of(DAY, listOf(verdict("V00", EvaluationStatus.SUCCESS), pending)))
        assertNull(DayVerdictMessage.of(DAY, emptyList()))
    }

    private fun closed(seq: Int, pnl: String, outcome: String, runId: String) =
        BuyVariationResult(seq, label(seq), runId, outcome, PositionState.CLOSED, BigDecimal(pnl))

    private fun idle(seq: Int, outcome: String, runId: String, state: PositionState?) =
        BuyVariationResult(seq, label(seq), runId, outcome, state, BigDecimal.ZERO)

    private fun refused(seq: Int, code: String) = BuyVariationResult(seq, label(seq), null, code, null, BigDecimal.ZERO)

    private fun label(seq: Int) = VariationEditRules.labelOf(seq)

    private fun verdict(label: String, status: EvaluationStatus, worstCase: String = "0", signals: Int = 0) =
        DayVerdict(DAY, PLAN, label, listOf(DAY), tally(signals, worstCase), DayStanding.Decided(status))

    private fun tally(signals: Int = 0, worstCase: String = "0") = EvaluationTally(signals, signals, 0, BigDecimal(worstCase), BigDecimal(worstCase))

    private companion object {
        const val PLAN = "decu-sweep-1"
        val DAY: LocalDate = LocalDate.parse("2026-11-05")
    }
}
