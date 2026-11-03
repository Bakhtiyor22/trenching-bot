package com.trenching.bot.console.automation.api

import com.trenching.bot.apiusage.ApiUsagePeriod
import com.trenching.bot.apiusage.ApiUsageReport
import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.apiusage.ApiUsageTotals
import com.trenching.bot.console.automation.AutomationDatabaseTest
import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationFixtures.WALLET
import com.trenching.bot.console.automation.AutomationFixtures.mint
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.AutomationRows.evaluation
import com.trenching.bot.console.automation.AutomationRows.item
import com.trenching.bot.console.automation.AutomationRows.session
import com.trenching.bot.console.automation.AutomationRows.sweepRun
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.automation.domain.EvaluationStatus
import com.trenching.bot.console.automation.domain.SweepPlans
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkEntity
import com.trenching.bot.console.automation.mapper.AutomationResponseMapperImpl
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.console.mapper.ConsoleErrorMapperImpl
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class AutomationControllerTest : AutomationDatabaseTest() {
    private val json = JsonMapper.builder().build()
    private val clock = MutableClock(EVENING)
    private val properties = properties()
    private val usage = ApiUsageReports { period, captureId ->
        check(period == ApiUsagePeriod.LAST_24_HOURS && captureId == null) { "unexpected usage report $period $captureId" }
        ApiUsageReport(EVENING.minus(Duration.ofHours(24)), EVENING, 300, null, ApiUsageTotals(42, 0, 0, 0, 0), emptyList(), emptyList(), emptyList(), emptyList())
    }
    private val mvc: MockMvc by lazy {
        MockMvcBuilders.standaloneSetup(AutomationController(query(properties, clock, usage), sessionService(properties, clock), AutomationResponseMapperImpl()))
            .setControllerAdvice(AutomationExceptionHandler(ConsoleErrorMapperImpl()))
            .build()
    }

    @Test
    fun `sessions without evaluations sort newest first by default`() {
        seedThreeNights()

        val body = ok(get("/api/automation/sessions"))

        assertEquals(listOf("tonight", "yesterday", "two-nights-ago"), ids(body))
        assertEquals(3, body["total"].asInt())
    }

    @Test
    fun `sessions sort by their best evaluation's status, then that evaluation's worst-case P&L, unevaluated ones last`() {
        val nights = listOf("success-low", "success-high", "no-success", "no-result", "success-beside-a-richer-no-result", "unevaluated")
        sessions.saveAll(nights.mapIndexed { day, id -> session(id, AutomationSessionState.CLOSED, EVENING.plus(Duration.ofDays(day.toLong()))) })
        evaluations.saveAll(listOf(
            evaluation("a1", "success-low", "V00", EvaluationStatus.SUCCESS, "1"),
            evaluation("a2", "success-low", "V01", EvaluationStatus.NO_RESULT, "2"),
            evaluation("b1", "success-high", "V00", EvaluationStatus.SUCCESS, "2"),
            evaluation("c1", "no-success", "V00", EvaluationStatus.NO_SUCCESS, "-0.1"),
            evaluation("d1", "no-result", "V00", EvaluationStatus.NO_RESULT, "50"),
            evaluation("e1", "success-beside-a-richer-no-result", "V00", EvaluationStatus.SUCCESS, "0.1"),
            evaluation("e2", "success-beside-a-richer-no-result", "V01", EvaluationStatus.NO_RESULT, "100"),
            evaluation("x1", "unevaluated", "V00", EvaluationStatus.SUCCESS, "9", planId = "decu-sweep-0"),
        ))

        val sorted = ids(ok(get("/api/automation/sessions?sort=status")))

        assertEquals(listOf("success-high", "success-low", "success-beside-a-richer-no-result", "no-success", "no-result", "unevaluated"), sorted)
        assertEquals(sorted, ids(ok(get("/api/automation/sessions"))))
    }

    @Test
    fun `sessions sort newest first on request`() {
        seedThreeNights()
        evaluations.save(evaluation("e1", "two-nights-ago", "V00", EvaluationStatus.SUCCESS, "1"))

        assertEquals(listOf("tonight", "yesterday", "two-nights-ago"), ids(ok(get("/api/automation/sessions?sort=createdAt,desc"))))
        assertEquals(listOf("two-nights-ago", "tonight", "yesterday"), ids(ok(get("/api/automation/sessions"))))
    }

    @Test
    fun `the health filter keeps sessions with a failed or abandoned item or a refused sweep entry, or those with none`() {
        val nights = listOf("failed", "abandoned", "refused", "clean")
        sessions.saveAll(nights.mapIndexed { day, id -> session(id, AutomationSessionState.CLOSED, EVENING.plus(Duration.ofDays(day.toLong()))) })
        items.saveAll(listOf(item(1, "failed", AutomationItemState.FAILED), item(2, "abandoned", AutomationItemState.ABANDONED),
            item(3, "refused", AutomationItemState.SIMULATED), item(4, "clean", AutomationItemState.SIMULATED),
            item(5, "clean", AutomationItemState.SKIPPED_NOT_FIRST_BUY)))
        sweepRuns.saveAll(listOf(
            sweepRun("r3", "item-3", "V00", sessionId = "refused"),
            sweepRun("r4", "item-4", "V00", sessionId = "clean", outcome = "REFERENCE_UNAVAILABLE", runId = "run-4"),
        ))

        assertEquals(listOf("refused", "abandoned", "failed"), ids(ok(get("/api/automation/sessions?health=ISSUES"))))
        assertEquals(listOf("clean"), ids(ok(get("/api/automation/sessions?health=OK&sort=createdAt,desc"))))
        assertEquals(4, ok(get("/api/automation/sessions"))["total"].asInt())
        assertEquals(400, mvc.perform(get("/api/automation/sessions?health=BROKEN")).andReturn().response.status)
    }

    @Test
    fun `from and to bound the first buy time and a page holds at most one hundred rows`() {
        seedThreeNights()

        val body = ok(get("/api/automation/sessions?from=${EVENING.minus(Duration.ofDays(1))}&to=$EVENING&size=500"))

        assertEquals(listOf("yesterday"), ids(body))
        assertEquals(100, body["size"].asInt())
    }

    @Test
    fun `rows count items by state and carry the best evaluation, which the status filter selects`() {
        seedThreeNights()
        items.saveAll(listOf(item(1, "yesterday"), item(2, "yesterday", AutomationItemState.FAILED), item(3, "tonight")))
        evaluations.saveAll(listOf(
            evaluation("e1", "yesterday", "V00", EvaluationStatus.NO_SUCCESS, "-0.5"),
            evaluation("e2", "yesterday", "V03", EvaluationStatus.SUCCESS, "0.25"),
            evaluation("e3", "two-nights-ago", "V01", EvaluationStatus.NO_SUCCESS, "-0.1"),
            evaluation("e4", "two-nights-ago", "V02", EvaluationStatus.NO_SUCCESS, "-0.2"),
            evaluation("e5", "tonight", "V00", EvaluationStatus.SUCCESS, "9", planId = "decu-sweep-0"),
        ))

        val all = ok(get("/api/automation/sessions?sort=createdAt,desc")).let { body -> List(body["items"].size()) { body["items"][it] } }
        val yesterday = all.single { it["id"].asString() == "yesterday" }
        assertEquals(1, yesterday["itemCounts"]["DISCOVERED"].asInt())
        assertEquals(1, yesterday["itemCounts"]["FAILED"].asInt())
        assertEquals(0, yesterday["itemCounts"]["SIMULATED"].asInt())
        assertEquals("SUCCESS", yesterday["bestStatus"].asString())
        assertEquals("V03", yesterday["bestLabel"].asString())
        assertEquals("V01", all.single { it["id"].asString() == "two-nights-ago" }["bestLabel"].asString())
        assertEquals(true, all.single { it["id"].asString() == "tonight" }["bestStatus"].isNull, "another plan's evaluations do not count")
        assertEquals(listOf("yesterday"), ids(ok(get("/api/automation/sessions?status=SUCCESS"))))
        assertEquals(listOf("two-nights-ago"), ids(ok(get("/api/automation/sessions?status=NO_SUCCESS"))))
    }

    @Test
    fun `an unknown sort is a 400 naming the sort`() {
        val response = mvc.perform(get("/api/automation/sessions?sort=name,asc")).andReturn().response

        assertEquals(400, response.status, response.contentAsString)
        assertEquals("INVALID_REQUEST", body(response)["code"].asString())
        assertEquals(true, body(response)["fields"].has("sort"))
    }

    @Test
    fun `an unknown status or a malformed time is a 400`() {
        listOf("/api/automation/sessions?status=GREAT", "/api/automation/sessions?from=yesterday", "/api/automation/sessions?page=-1").forEach { path ->
            val response = mvc.perform(get(path)).andReturn().response
            assertEquals(400, response.status, "$path ${response.contentAsString}")
            assertEquals("INVALID_REQUEST", body(response)["code"].asString())
        }
    }

    @Test
    fun `an unknown session is a 404`() {
        listOf(get("/api/automation/sessions/missing"), closeRequest("missing", "close-1")).forEach { request ->
            val response = mvc.perform(request).andReturn().response
            assertEquals(404, response.status, response.contentAsString)
            assertEquals("AUTOMATION_SESSION_NOT_FOUND", body(response)["code"].asString())
        }
    }

    @Test
    fun `after seven closed sessions the scoreboard lists the last session's ten entries by status, then worst-case P&L`() {
        val nights = (1..7).map { day -> "night-$day" }
        nights.forEachIndexed { index, id -> sessions.save(session(id, AutomationSessionState.CLOSED, EVENING.plus(Duration.ofDays(index.toLong())))) }
        nights.forEachIndexed { index, sessionId ->
            listOf(2 * index + 1, 2 * index + 2).forEach { n ->
                items.save(item(n, sessionId, AutomationItemState.SIMULATED).apply { step = AutomationStep.SWEEP })
                sweepRuns.saveAll(PLAN_LABELS.mapIndexed { k, label ->
                    if (k >= 8) {
                        sweepRun("r$n-$label", "item-$n", label, sessionId = sessionId, outcome = "SIGNAL_BELOW_MIN_SIZE", runId = "run-$n-$label")
                    } else {
                        sweepRun("r$n-$label", "item-$n", label, sessionId = sessionId, outcome = "TAKE_PROFIT", runId = "run-$n-$label",
                            signal = true, worstCasePnl = BigDecimal(k - 4).movePointLeft(2).setScale(12).toPlainString())
                    }
                })
            }
        }
        evaluationService(properties(captureEnabled = true), clock).evaluateClosedSessions()

        val body = ok(get("/api/automation/evaluations?from=${EVENING.plus(Duration.ofDays(6))}"))
        val rows = List(body["items"].size()) { body["items"][it] }

        assertEquals(10, body["total"].asInt())
        assertEquals(listOf("V07", "V06", "V05", "V04", "V03", "V02", "V01", "V00", "V08", "V09"), rows.map { it["label"].asString() })
        assertEquals(List(3) { "SUCCESS" } + List(5) { "NO_SUCCESS" } + List(2) { "NO_RESULT" }, rows.map { it["status"].asString() })
        assertEquals("0.420000000000", rows[0]["worstCasePnl"].asString(), "14 signals at 0.03")
        assertEquals("0.000000000000", rows[3]["worstCasePnl"].asString())
        assertEquals(nights, List(rows[0]["windowSessionIds"].size()) { rows[0]["windowSessionIds"][it].asString() })
        assertEquals(listOf(14, 14, 0), listOf(rows[0]["signals"].asInt(), rows[0]["scored"].asInt(), rows[0]["unscored"].asInt()))
        assertEquals(listOf(0, 14, 0), listOf(rows[9]["signals"].asInt(), rows[9]["scored"].asInt(), rows[9]["unscored"].asInt()))
        assertEquals("night-7", rows[0]["sessionId"].asString())
        val detail = ok(get("/api/automation/sessions/night-7"))
        assertEquals(PLAN_LABELS, List(detail["evaluations"].size()) { detail["evaluations"][it]["label"].asString() })
        assertEquals("SUCCESS", detail["session"]["bestStatus"].asString())
        assertEquals("V07", detail["session"]["bestLabel"].asString())
    }

    @Test
    fun `the scoreboard filters by plan, label, status and first buy time, and sorts newest first on request`() {
        seedThreeNights()
        evaluations.saveAll(listOf(
            evaluation("e1", "yesterday", "V00", EvaluationStatus.NO_SUCCESS, "-0.5", evaluatedAt = EVENING.minus(Duration.ofDays(1))),
            evaluation("e2", "yesterday", "V03", EvaluationStatus.SUCCESS, "0.25", evaluatedAt = EVENING.minus(Duration.ofDays(1))),
            evaluation("e3", "two-nights-ago", "V00", EvaluationStatus.SUCCESS, "0.75", evaluatedAt = EVENING.minus(Duration.ofDays(2))),
            evaluation("e4", "tonight", "V00", EvaluationStatus.NO_RESULT, "3", planId = "decu-sweep-0", evaluatedAt = EVENING),
        ))

        assertEquals(listOf("e3", "e2", "e1", "e4"), evaluationIds(ok(get("/api/automation/evaluations"))))
        assertEquals(listOf("e4", "e1", "e2", "e3"), evaluationIds(ok(get("/api/automation/evaluations?sort=evaluatedAt,desc"))))
        assertEquals(listOf("e3", "e2", "e1"), evaluationIds(ok(get("/api/automation/evaluations?planId=decu-sweep-1"))))
        assertEquals(listOf("e3", "e1", "e4"), evaluationIds(ok(get("/api/automation/evaluations?label=V00"))))
        assertEquals(listOf("e3", "e2"), evaluationIds(ok(get("/api/automation/evaluations?status=SUCCESS"))))
        assertEquals(listOf("e2", "e1"), evaluationIds(ok(get("/api/automation/evaluations?from=${EVENING.minus(Duration.ofDays(1))}&to=$EVENING"))))
        assertEquals(100, ok(get("/api/automation/evaluations?size=1000"))["size"].asInt())
    }

    @Test
    fun `the scoreboard and the sweep run list refuse a bad parameter with a 400 naming it`() {
        mapOf(
            "/api/automation/evaluations?sort=label" to "sort",
            "/api/automation/evaluations?status=GREAT" to "status",
            "/api/automation/evaluations?size=0" to "size",
            "/api/automation/evaluations?from=yesterday" to "from",
            "/api/automation/sweep-runs?outcome=WINNING" to "outcome",
            "/api/automation/sweep-runs?mint=not-a-mint" to "mint",
            "/api/automation/sweep-runs?page=-1" to "page",
        ).forEach { (path, field) ->
            val response = mvc.perform(get(path)).andReturn().response
            assertEquals(400, response.status, "$path ${response.contentAsString}")
            assertEquals("INVALID_REQUEST", body(response)["code"].asString())
            assertEquals(true, body(response)["fields"].has(field), "$path ${response.contentAsString}")
        }
    }

    @Test
    fun `sweep runs list newest buy first with their buy, and filter by session, mint, label and outcome`() {
        seedThreeNights()
        items.saveAll(listOf(
            item(1, "yesterday", AutomationItemState.SIMULATED, blockTime = EVENING.minus(Duration.ofDays(1))),
            item(2, "tonight", AutomationItemState.SIMULATED, blockTime = EVENING),
        ))
        sweepRuns.saveAll(listOf(
            sweepRun("r1a", "item-1", "V00", sessionId = "yesterday", outcome = "TAKE_PROFIT", runId = "run-1a", signal = true, worstCasePnl = "0.03"),
            sweepRun("r1b", "item-1", "V01", sessionId = "yesterday"),
            sweepRun("r2a", "item-2", "V00", sessionId = "tonight", outcome = "NO_DIP", runId = "run-2a", signal = true),
        ))

        val all = ok(get("/api/automation/sweep-runs"))

        assertEquals(listOf("item-2:V00", "item-1:V00", "item-1:V01"), runKeys(all))
        assertEquals(true, all["items"][2]["runId"].isNull)
        assertEquals(mint(2).value, all["items"][0]["mint"].asString())
        assertEquals("tonight", all["items"][0]["sessionId"].asString())
        assertEquals(EVENING.toString(), all["items"][0]["blockTime"].asString())
        assertEquals("0.030000000000", all["items"][1]["worstCasePnl"].asString())
        assertEquals(listOf("item-1:V00", "item-1:V01"), runKeys(ok(get("/api/automation/sweep-runs?sessionId=yesterday"))))
        assertEquals(listOf("item-1:V00", "item-1:V01"), runKeys(ok(get("/api/automation/sweep-runs?mint=${mint(1).value}"))))
        assertEquals(listOf("item-2:V00", "item-1:V00"), runKeys(ok(get("/api/automation/sweep-runs?label=V00"))))
        assertEquals(listOf("item-1:V01"), runKeys(ok(get("/api/automation/sweep-runs?outcome=MARKET_COVERAGE_ENDED"))))
        assertEquals(0, ok(get("/api/automation/sweep-runs?outcome=STOP_LOSS"))["total"].asInt())
        assertEquals(listOf("V00", "V01"), ok(get("/api/automation/items/item-1"))["sweepRuns"].let { runs -> List(runs.size()) { runs[it]["label"].asString() } })
    }

    @Test
    fun `a session's detail lists its items oldest buy first and no evaluations yet`() {
        seedThreeNights()
        items.saveAll(listOf(item(1, "tonight", blockTime = EVENING.plusSeconds(20)), item(2, "tonight", blockTime = EVENING.plusSeconds(10))))

        val body = ok(get("/api/automation/sessions/tonight"))

        assertEquals("tonight", body["session"]["id"].asString())
        assertEquals("OPEN", body["session"]["state"].asString())
        assertEquals(listOf("item-2", "item-1"), List(body["items"].size()) { body["items"][it]["id"].asString() })
        assertEquals("2000000000", body["items"][0]["curvePaymentLamports"].asString())
        assertEquals(0, body["evaluations"].size())
    }

    @Test
    fun `an item's detail carries its capture progress and requests, with no sweep runs yet`() {
        seedThreeNights()
        items.save(item(1, "tonight", AutomationItemState.CAPTURING, blockTime = EVENING, nextActionAt = EVENING).apply {
            step = AutomationStep.MARKET
            stepAttempt = 2
            captureSessionId = "auto-create_session-abc-1"
            operationId = "auto-market-abc-2"
            failureCode = "TIMEOUT"
            failureMessage = "Capture operation stopped: TIMEOUT."
            requestsUsed = 321
        })

        val body = ok(get("/api/automation/items/item-1"))
        val item = body["item"]

        assertEquals("item-1", item["id"].asString())
        assertEquals("tonight", item["sessionId"].asString())
        assertEquals("CAPTURING", item["state"].asString())
        assertEquals("MARKET", item["step"].asString())
        assertEquals(2, item["stepAttempt"].asInt())
        assertEquals("auto-create_session-abc-1", item["captureSessionId"].asString())
        assertEquals("auto-market-abc-2", item["operationId"].asString())
        assertEquals("TIMEOUT", item["failureCode"].asString())
        assertEquals(321, item["requestsUsed"].asInt())
        assertEquals(EVENING.toString(), item["nextActionAt"].asString())
        assertEquals(true, item["datasetId"].isNull)
        assertEquals(0, body["sweepRuns"].size())
        assertEquals(321, ok(get("/api/automation/sessions/tonight"))["session"]["requestsUsed"].asInt())
    }

    @Test
    fun `an unknown item is a 404`() {
        val response = mvc.perform(get("/api/automation/items/missing")).andReturn().response

        assertEquals(404, response.status, response.contentAsString)
        assertEquals("AUTOMATION_ITEM_NOT_FOUND", body(response)["code"].asString())
    }

    @Test
    fun `closing a session returns it closed by hand, and a close without a request id is a 400`() {
        seedThreeNights()
        items.save(item(1, "tonight"))

        val missing = mvc.perform(post("/api/automation/sessions/tonight/close").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response
        val closed = ok(closeRequest("tonight", "close-1"))

        assertEquals(400, missing.status, missing.contentAsString)
        assertEquals("CLOSED", closed["session"]["state"].asString())
        assertEquals("MANUAL", closed["session"]["closeReason"].asString())
        assertEquals("ABANDONED", closed["items"][0]["state"].asString())
    }

    @Test
    fun `status reports the settings, the watermark, the day's requests against the cap and the open session`() {
        seedThreeNights()
        watermarks.save(AutomationWatermarkEntity(WALLET, 123, "sig-1", 2, -1, EVENING))

        val body = ok(get("/api/automation/status"))

        assertEquals(true, body["enabled"].asBoolean())
        assertEquals(false, body["captureEnabled"].asBoolean())
        assertEquals(WALLET, body["wallet"].asString())
        assertEquals("+05:00", body["zone"].asString())
        assertEquals(true, body["inPollWindow"].asBoolean())
        assertEquals(123, body["watermark"]["slot"].asLong())
        assertEquals(42, body["requestsLast24h"].asLong())
        assertEquals(5000, body["maxRequestsPer24h"].asLong())
        assertEquals("tonight", body["openSessionId"].asString())
        assertEquals("decu-sweep-1", body["sweepPlanId"].asString())
    }

    private fun seedThreeNights() {
        sessions.saveAll(listOf(
            session("two-nights-ago", AutomationSessionState.CLOSED, EVENING.minus(Duration.ofDays(2))),
            session("yesterday", AutomationSessionState.CLOSED, EVENING.minus(Duration.ofDays(1))),
            session("tonight", AutomationSessionState.OPEN, EVENING),
        ))
    }

    private fun closeRequest(id: String, requestId: String) =
        post("/api/automation/sessions/$id/close").contentType(MediaType.APPLICATION_JSON).content("""{"requestId":"$requestId"}""")

    private fun ok(request: org.springframework.test.web.servlet.RequestBuilder): JsonNode {
        val response = mvc.perform(request).andReturn().response
        assertEquals(200, response.status, response.contentAsString)
        return body(response)
    }

    private fun body(response: MockHttpServletResponse): JsonNode = json.readTree(response.contentAsString)

    private fun ids(body: JsonNode): List<String> = List(body["items"].size()) { body["items"][it]["id"].asString() }

    private fun runKeys(body: JsonNode): List<String> =
        List(body["items"].size()) { body["items"][it]["itemId"].asString() + ":" + body["items"][it]["label"].asString() }

    /** Scoreboard rows by the id each evaluation was stored under, found by its session, plan and label. */
    private fun evaluationIds(body: JsonNode): List<String> = List(body["items"].size()) { index ->
        val row = body["items"][index]
        evaluations.findAll().single { it.sessionId == row["sessionId"].asString() && it.planId == row["planId"].asString() && it.label == row["label"].asString() }.id
    }

    private companion object {
        val PLAN_LABELS: List<String> = SweepPlans.DECU_SWEEP_1.entries.map { it.label }
    }
}
