package com.trenching.bot.console.automation.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.helpers.MessageFormatter

/** Plan Domain Rules 2–4: an item change becomes its line, at its level, with the buy's context. */
class AutomationItemLinesTest {
    private val calendar = TradingCalendar(LocalTime.NOON, ZoneOffset.ofHours(5))
    private val buyAt = Instant.parse("2026-11-20T21:14:03Z")
    private val live = AutomationItemSubject("item-1", "mint-1", "wallet-1", null, buyAt, "session-1", null, null, null, null)
    private val backfill = live.copy(backfillId = "backfill-1")

    private fun lines(subject: AutomationItemSubject, vararg events: AutomationItemEventDraft, before: AutomationItemProgress? = null) =
        AutomationItemLines.of(subject, AutomationItemChange(before?.let { AutomationItemStanding(it, null) }, events.toList()), calendar, MAX_ATTEMPTS)

    private fun message(line: AutomationItemLogLine): String = MessageFormatter.arrayFormat(line.format, line.arguments.toTypedArray()).message

    private fun field(line: AutomationItemLogLine, key: String): Any? = line.fields.firstOrNull { it.first == key }?.second

    private fun found(state: AutomationItemState = AutomationItemState.DISCOVERED, code: String? = null) =
        AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, toState = state, failureCode = code)

    @Test
    fun `a live buy found is an info line with its buy time, trading day and session`() {
        val line = lines(live, found()).single()

        assertEquals(AutomationLogLevel.INFO, line.level)
        assertEquals("Buy found.", message(line))
        assertEquals(buyAt, field(line, "buyAt"))
        assertEquals("2026-11-20", field(line, "tradingDay").toString())
        assertEquals("session-1", field(line, "sessionId"))
        assertEquals(AutomationActivityKind.LIVE_FOUND, line.activity)
    }

    @Test
    fun `a backfill's buy found is a debug line that names its backfill`() {
        val line = lines(backfill, found()).single()

        assertEquals(AutomationLogLevel.DEBUG, line.level)
        assertEquals("backfill-1", field(line, "backfillId"))
        assertEquals(AutomationActivityKind.BACKFILL_FOUND, line.activity)
    }

    @Test
    fun `every line carries the item, token and wallet, and a live buy has no backfill key at all`() {
        val line = lines(live, found()).single()

        assertEquals(listOf("item-1", "mint-1", "wallet-1"), listOf("itemId", "mint", "wallet").map { field(line, it) })
        assertFalse(line.fields.any { it.first == "backfillId" })
        assertTrue(line.fields.none { it.second == null })
    }

    @Test
    fun `bookkeeping changes are debug lines`() {
        val moves = listOf(
            AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.DISCOVERED, AutomationItemState.CAPTURING, AutomationStep.WALLET, captureSessionId = "c"),
            AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURING, AutomationItemState.CAPTURING, AutomationStep.MARKET),
            AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURING, AutomationItemState.WAITING, AutomationStep.MARKET),
            AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURING, AutomationItemState.BLOCKED_BUDGET, AutomationStep.MARKET),
            AutomationItemEventDraft(AutomationItemEventKind.OPERATION_STARTED, step = AutomationStep.MARKET, attempt = 1, operationId = "op"),
            AutomationItemEventDraft(AutomationItemEventKind.OPERATION_ENDED, step = AutomationStep.MARKET, attempt = 1, operationId = "op", detail = mapOf("operationState" to "SUCCEEDED")),
            AutomationItemEventDraft(AutomationItemEventKind.SWEEP_ENTRY_RECORDED, step = AutomationStep.SWEEP, detail = mapOf("label" to "a", "outcome" to "SIGNAL")),
            AutomationItemEventDraft(AutomationItemEventKind.HISTORY_STARTED, toState = AutomationItemState.CAPTURING, step = AutomationStep.WALLET),
        )

        val produced = moves.map { lines(live, it).single() }

        assertTrue(produced.all { it.level == AutomationLogLevel.DEBUG && it.activity == null }, produced.map(::message).toString())
        assertEquals("market", field(produced[1], "step"))
    }

    @Test
    fun `a failed attempt that another follows is a warn line with the code, the meaning and attempt n of max`() {
        val attempt = AutomationItemEventDraft(AutomationItemEventKind.ATTEMPT_FAILED, step = AutomationStep.MARKET, attempt = 1, failureCode = "REQUESTS")

        val line = lines(live, attempt).single()

        assertEquals(AutomationLogLevel.WARN, line.level)
        assertEquals("Buy capture will retry the market step: the operation's request budget ran out.", message(line))
        assertEquals("REQUESTS", field(line, "code"))
        assertEquals("1/2", field(line, "attempt"))
        assertEquals("market", field(line, "step"))
        assertEquals(AutomationActivityKind.RETRIED_ATTEMPT, line.activity)
    }

    @Test
    fun `a failed attempt that ends the item is one warn line with the item's requests, not a retry and not two lines`() {
        val standing = live.copy(requestsUsed = 1841)
        val attempt = AutomationItemEventDraft(AutomationItemEventKind.ATTEMPT_FAILED, step = AutomationStep.MARKET, attempt = 1, failureCode = "OVER_CAP")
        val state = AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURING, AutomationItemState.FAILED, AutomationStep.MARKET, attempt = 1)

        val produced = lines(standing, attempt, state)
        val line = produced.single()

        assertEquals(AutomationLogLevel.WARN, line.level)
        assertTrue(message(line).startsWith("Buy failed at the market step and will not be retried: "), message(line))
        assertTrue(message(line).contains("market window lists more trades"), message(line))
        assertEquals("OVER_CAP", field(line, "code"))
        assertEquals("1/2", field(line, "attempt"))
        assertEquals(1841, field(line, "requestsUsed"))
        assertEquals(AutomationActivityKind.FAILED, line.activity)
        assertEquals("OVER_CAP", line.code)
    }

    @Test
    fun `an item that fails without an attempt, such as a deleted capture, is a failed warn line with its code`() {
        val lost = AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURING, AutomationItemState.FAILED, AutomationStep.WALLET, attempt = 1,
            failureCode = AutomationFailures.CAPTURE_NOT_FOUND)

        val line = lines(backfill, lost).single()

        assertEquals(AutomationLogLevel.WARN, line.level)
        assertTrue(message(line).startsWith("Buy failed at the wallet step and will not be retried: "))
        assertEquals(AutomationFailures.CAPTURE_NOT_FOUND, field(line, "code"))
        assertEquals("backfill-1", field(line, "backfillId"))
    }

    @Test
    fun `an item a backfill's spent budget stops is a debug line, since the pipeline says once that the budget is spent, and it still counts as failed`() {
        val stopped = AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURING, AutomationItemState.FAILED, AutomationStep.WALLET, attempt = 1,
            failureCode = AutomationFailures.BACKFILL_BUDGET)

        val line = lines(backfill, stopped).single()

        assertEquals(AutomationLogLevel.DEBUG, line.level)
        assertTrue(message(line).startsWith("Buy failed at the wallet step and will not be retried: "), message(line))
        assertEquals(AutomationActivityKind.FAILED, line.activity)
        assertEquals(AutomationFailures.BACKFILL_BUDGET, line.code)
    }

    @Test
    fun `a buy found already failed for its missing block time is found and then failed before capture`() {
        val untimed = live.copy(blockTime = null)

        val produced = lines(untimed, found(AutomationItemState.FAILED, AutomationFailures.MISSING_BLOCK_TIME))

        assertEquals(listOf(AutomationLogLevel.INFO, AutomationLogLevel.WARN), produced.map { it.level })
        assertTrue(message(produced[1]).startsWith("Buy failed before capture started and will not be retried: "))
        assertNull(field(produced[0], "buyAt"))
        assertNull(field(produced[0], "tradingDay"))
    }

    @Test
    fun `a failed sweep attempt is retried or ends the item in the sweep step`() {
        val attempt = AutomationItemEventDraft(AutomationItemEventKind.ATTEMPT_FAILED, step = AutomationStep.SWEEP, attempt = 1, failureCode = AutomationFailures.SWEEP_FAILED)
        val failed = AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURED, AutomationItemState.FAILED, AutomationStep.SWEEP, attempt = 1)

        assertTrue(message(lines(live, attempt).single()).startsWith("Buy capture will retry the sweep step: "))
        assertFalse(message(lines(live, attempt).single()).contains("every attempt"), "a retry does not say every attempt failed")
        assertTrue(message(lines(live, attempt, failed).single()).contains("every attempt"), message(lines(live, attempt, failed).single()))
        assertTrue(message(lines(live, attempt, failed).single()).startsWith("Buy failed at the sweep step and will not be retried: "))
    }

    @Test
    fun `a skipped buy says why from its classification`() {
        val skipped = AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURING, AutomationItemState.SKIPPED_NOT_FIRST_BUY, AutomationStep.QUALIFY)

        val line = lines(live.copy(classification = "FIRST_BUY_UNPROVEN"), skipped).single()

        assertEquals(AutomationLogLevel.INFO, line.level)
        assertEquals("Buy skipped: ${AutomationFirstBuy.meaning("FIRST_BUY_UNPROVEN")}.", message(line))
        assertEquals("FIRST_BUY_UNPROVEN", field(line, "classification"))
        assertEquals(AutomationActivityKind.SKIPPED, line.activity)
    }

    @Test
    fun `a captured buy is an info line with its dataset and requests`() {
        val captured = AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURING, AutomationItemState.CAPTURED, AutomationStep.REGISTER)

        val line = lines(live.copy(datasetId = "dataset-1", requestsUsed = 1834), captured).single()

        assertEquals(AutomationLogLevel.INFO, line.level)
        assertEquals("Buy captured and registered as a dataset; the sweep runs next.", message(line))
        assertEquals("dataset-1", field(line, "datasetId"))
        assertEquals(1834, field(line, "requestsUsed"))
        assertEquals(AutomationActivityKind.CAPTURED, line.activity)
    }

    @Test
    fun `a simulated buy is an info line with the runs its sweep made`() {
        val simulated = AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURED, AutomationItemState.SIMULATED, AutomationStep.SWEEP)

        val line = lines(live.copy(sweepRuns = 24), simulated).single()

        assertEquals(AutomationLogLevel.INFO, line.level)
        assertEquals("Buy simulated: every sweep configuration ran on it.", message(line))
        assertEquals(24, field(line, "runs"))
        assertNull(field(line, "refused"))
        assertEquals(AutomationActivityKind.SIMULATED, line.activity)
    }

    @Test
    fun `a simulated buy whose sweep rows hold refused entries says how many`() {
        val simulated = AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURED, AutomationItemState.SIMULATED, AutomationStep.SWEEP)

        val line = lines(live.copy(sweepRuns = 24, sweepRefused = 3), simulated).single()

        assertEquals(3, field(line, "refused"))
        assertEquals(24, field(line, "runs"))
    }

    @Test
    fun `an abandoned buy is an info line`() {
        val abandoned = AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.CAPTURING, AutomationItemState.ABANDONED, AutomationStep.WALLET, requestId = "r")

        val line = lines(live, abandoned).single()

        assertEquals(AutomationLogLevel.INFO, line.level)
        assertEquals("Buy abandoned: its session was closed by hand.", message(line))
        assertEquals(AutomationActivityKind.ABANDONED, line.activity)
    }

    @Test
    fun `a person's retry is one info line with the failure it replaces and the request id`() {
        val failed = AutomationItemProgress(AutomationItemState.FAILED, AutomationStep.MARKET, 2, "c", "op", null, null, "OVER_CAP", null, null)
        val retried = AutomationItemEventDraft(AutomationItemEventKind.RETRY_REQUESTED, AutomationItemState.FAILED, AutomationItemState.CAPTURING, AutomationStep.MARKET, requestId = "request-9")

        val line = lines(live, retried, before = failed).single()

        assertEquals(AutomationLogLevel.INFO, line.level)
        assertEquals("Buy retried from the console.", message(line))
        assertEquals("OVER_CAP", field(line, "code"))
        assertEquals("request-9", field(line, "requestId"))
        assertEquals(AutomationActivityKind.RETRIED_BY_PERSON, line.activity)
    }

    @Test
    fun `a buy an earlier buy of its token replaced is an info line in words`() {
        val replaced = AutomationItemEventDraft(AutomationItemEventKind.REPLACED, fromState = AutomationItemState.DISCOVERED, detail = mapOf("replacedBy" to "item-0"))

        val line = lines(live, replaced).single()

        assertEquals(AutomationLogLevel.INFO, line.level)
        assertTrue(message(line).startsWith("Buy replaced: "), message(line))
        assertEquals("item-0", field(line, "replacedBy"))
        assertNull(line.activity)
    }

    @Test
    fun `a backfill's replaced buys are debug lines, a live night's an info line`() {
        val replaced = AutomationItemEventDraft(AutomationItemEventKind.REPLACED, fromState = AutomationItemState.DISCOVERED, detail = mapOf("replacedBy" to "item-0"))
        val retaken = AutomationItemEventDraft(AutomationItemEventKind.STATE_CHANGED, AutomationItemState.FAILED, AutomationItemState.DISCOVERED,
            detail = mapOf("reason" to AutomationItemEvents.RETAKEN_BY_BACKFILL, "backfillId" to "backfill-1"))

        assertEquals(AutomationLogLevel.DEBUG, lines(backfill, replaced).single().level)
        assertEquals(AutomationLogLevel.DEBUG, lines(backfill, retaken).single().level)
        assertEquals(AutomationLogLevel.INFO, lines(live, replaced).single().level)
        assertEquals(AutomationLogLevel.INFO, lines(live, retaken).single().level)
    }

    @Test
    fun `an unknown failure code still logs with the fallback clause and the raw code`() {
        val attempt = AutomationItemEventDraft(AutomationItemEventKind.ATTEMPT_FAILED, step = AutomationStep.WALLET, attempt = 1, failureCode = "SOMETHING_NEW")

        val line = lines(live, attempt).single()

        assertTrue(message(line).contains(AutomationFailures.meaning("SOMETHING_NEW")))
        assertEquals("SOMETHING_NEW", field(line, "code"))
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}
