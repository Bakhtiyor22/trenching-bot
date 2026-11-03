package com.trenching.bot.console.automation.api

import com.trenching.bot.apiusage.ApiUsagePeriod
import com.trenching.bot.apiusage.ApiUsageReport
import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.apiusage.ApiUsageTotals
import com.trenching.bot.console.automation.AutomationDatabaseTest
import com.trenching.bot.console.automation.AutomationDaySeed
import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationFixtures.WALLET
import com.trenching.bot.console.automation.AutomationFixtures.buy
import com.trenching.bot.console.automation.AutomationFixtures.mint
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.AutomationRows.item
import com.trenching.bot.console.automation.AutomationRows.session
import com.trenching.bot.console.automation.AutomationRows.sweepRun
import com.trenching.bot.console.automation.FakeWalletActivityLookup
import com.trenching.bot.console.automation.RecordingTaskScheduler
import com.trenching.bot.console.automation.application.AutomationManualRun
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkEntity
import com.trenching.bot.console.automation.mapper.AutomationBackfillRequestMapperImpl
import com.trenching.bot.console.automation.mapper.AutomationResponseMapperImpl
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.console.mapper.ConsoleErrorMapperImpl
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class AutomationControllerTest : AutomationDatabaseTest() {
    private val json = JsonMapper.builder().build()
    private val clock = MutableClock(EVENING)
    private val properties = properties()
    private val scheduler = RecordingTaskScheduler()
    private val lookup = FakeWalletActivityLookup()
    private val usage = ApiUsageReports { period, captureId ->
        check(period == ApiUsagePeriod.LAST_24_HOURS && captureId == null) { "unexpected usage report $period $captureId" }
        ApiUsageReport(EVENING.minus(Duration.ofHours(24)), EVENING, 300, null, ApiUsageTotals(42, 0, 0, 0, 0), emptyList(), emptyList(), emptyList(), emptyList())
    }
    private val seed by lazy { AutomationDaySeed(sessions, items, sweepRuns) }
    private val mvc: MockMvc by lazy { mvc(properties, AutomationManualRun(properties, runtime, discovery(lookup, properties, clock), scheduler, clock)) }

    private fun mvc(properties: AutomationProperties, manualRun: AutomationManualRun): MockMvc =
        MockMvcBuilders.standaloneSetup(AutomationController(query(properties, clock, usage), dayService(properties, clock), sessionService(properties, clock),
            manualRun, backfillService(properties, clock), backfillQuery(properties), AutomationResponseMapperImpl(), AutomationBackfillRequestMapperImpl()))
            .setControllerAdvice(AutomationExceptionHandler(ConsoleErrorMapperImpl()))
            .build()

    @Test
    fun `sessions list newest first by default, with their trading day and no evaluation fields`() {
        seedThreeNights()

        val body = ok(get("/api/automation/sessions"))

        assertEquals(listOf("tonight", "yesterday", "two-nights-ago"), ids(body))
        assertEquals(3, body["total"].asInt())
        assertEquals("2026-11-02", body["items"][0]["tradingDay"].asString())
        assertEquals("2026-11-01", body["items"][1]["tradingDay"].asString(), "20:00 local the night before")
        assertEquals(false, body["items"][0].has("bestStatus"))
        assertEquals(false, body["items"][0].has("bestLabel"))
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
    fun `rows count items by state`() {
        seedThreeNights()
        items.saveAll(listOf(item(1, "yesterday"), item(2, "yesterday", AutomationItemState.FAILED), item(3, "tonight")))

        val all = ok(get("/api/automation/sessions")).let { body -> List(body["items"].size()) { body["items"][it] } }
        val yesterday = all.single { it["id"].asString() == "yesterday" }

        assertEquals(1, yesterday["itemCounts"]["DISCOVERED"].asInt())
        assertEquals(1, yesterday["itemCounts"]["FAILED"].asInt())
        assertEquals(0, yesterday["itemCounts"]["SIMULATED"].asInt())
    }

    @Test
    fun `an unknown sort is a 400 naming the sort`() {
        val response = mvc.perform(get("/api/automation/sessions?sort=name,asc")).andReturn().response

        assertEquals(400, response.status, response.contentAsString)
        assertEquals("INVALID_REQUEST", body(response)["code"].asString())
        assertEquals(true, body(response)["fields"].has("sort"))
    }

    @Test
    fun `the removed status sort is still accepted as a deprecated alias of the default order, so a page that sends it keeps working`() {
        seedThreeNights()

        assertEquals(ids(ok(get("/api/automation/sessions"))), ids(ok(get("/api/automation/sessions?sort=status"))))
    }

    @Test
    fun `a malformed time or page is a 400`() {
        listOf("/api/automation/sessions?from=yesterday", "/api/automation/sessions?page=-1").forEach { path ->
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
    fun `after seven complete days the last day's ten rows are listed by status, then worst-case P&L`() {
        seed.week()
        coverFrom("2026-11-01")

        val body = ok(get("/api/automation/evaluations?from=2026-11-11&to=2026-11-11"))
        val rows = List(body["items"].size()) { body["items"][it] }

        assertEquals(10, body["total"].asInt())
        assertEquals(listOf("V07", "V06", "V05", "V04", "V03", "V02", "V01", "V00", "V08", "V09"), rows.map { it["label"].asString() })
        assertEquals(List(3) { "SUCCESS" } + List(5) { "NO_SUCCESS" } + List(2) { "NO_RESULT" }, rows.map { it["status"].asString() })
        assertEquals("0.420000000000", rows[0]["worstCasePnl"].asString(), "14 signals at 0.03")
        assertEquals("0.000000000000", rows[3]["worstCasePnl"].asString())
        assertEquals((5..11).map { AutomationDaySeed.date(it) }, List(rows[0]["windowDays"].size()) { rows[0]["windowDays"][it].asString() })
        assertEquals(listOf(14, 14, 0), listOf(rows[0]["signals"].asInt(), rows[0]["scored"].asInt(), rows[0]["unscored"].asInt()))
        assertEquals(listOf(0, 14, 0), listOf(rows[9]["signals"].asInt(), rows[9]["scored"].asInt(), rows[9]["unscored"].asInt()))
        assertEquals("2026-11-11", rows[0]["day"].asString())
        assertEquals("decu-sweep-1", rows[0]["planId"].asString())
        assertEquals(true, rows[0]["pendingDay"].isNull)
        assertEquals(false, rows[0].has("sessionId"))
    }

    @Test
    fun `an incomplete day makes the day's rows pending on the oldest incomplete day, and covering it later gives verdicts`() {
        seed.week()
        coverFrom("2026-11-05", "20:00")

        val pending = ok(get("/api/automation/evaluations?from=2026-11-11&to=2026-11-11"))
        coverFrom("2026-11-01")
        val decided = ok(get("/api/automation/evaluations?from=2026-11-11&to=2026-11-11"))

        assertEquals(setOf("PENDING"), List(pending["items"].size()) { pending["items"][it]["status"].asString() }.toSet())
        assertEquals(setOf("2026-11-05"), List(pending["items"].size()) { pending["items"][it]["pendingDay"].asString() }.toSet())
        assertEquals("SUCCESS", decided["items"][0]["status"].asString())
    }

    @Test
    fun `the evaluations filter by label and status, sort by day on request, and refuse a bad parameter with a 400 naming it`() {
        seed.week()
        coverFrom("2026-11-01")

        val labelled = ok(get("/api/automation/evaluations?from=2026-11-09&to=2026-11-11&label=V07&sort=day,desc"))
        assertEquals(listOf("2026-11-11", "2026-11-10", "2026-11-09"), List(labelled["items"].size()) { labelled["items"][it]["day"].asString() })
        assertEquals(3, ok(get("/api/automation/evaluations?from=2026-11-11&to=2026-11-11&status=SUCCESS"))["total"].asInt())
        assertEquals(100, ok(get("/api/automation/evaluations?from=2026-09-12&to=2026-11-11&size=1000"))["size"].asInt())
        mapOf(
            "/api/automation/evaluations?sort=label" to "sort",
            "/api/automation/evaluations?status=GREAT" to "status",
            "/api/automation/evaluations?size=0" to "size",
            "/api/automation/evaluations?from=yesterday" to "from",
            "/api/automation/evaluations?from=2026-11-11&to=2026-11-10" to "from",
            "/api/automation/evaluations?from=2026-09-10&to=2026-11-11" to "to",
            "/api/automation/days?from=2026-11-11&to=2026-11-10" to "from",
            "/api/automation/days?to=11-11-2026" to "to",
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
    fun `days list newest first with their counts, completeness and best standing`() {
        seed.week()
        coverFrom("2026-11-01")

        val body = ok(get("/api/automation/days?from=2026-11-10&to=2026-11-12"))
        val days = List(body["items"].size()) { body["items"][it] }

        assertEquals("2026-11-10", body["from"].asString())
        assertEquals("2026-11-12", body["to"].asString())
        assertEquals(listOf("2026-11-12", "2026-11-11", "2026-11-10"), days.map { it["day"].asString() })
        assertEquals(true, days[1]["complete"].asBoolean())
        assertEquals(1, days[1]["sessions"].asInt())
        assertEquals(listOf(2, 2, 2, 0, 0), listOf(days[1]["buys"].asInt(), days[1]["captured"].asInt(), days[1]["simulated"].asInt(), days[1]["failed"].asInt(), days[1]["abandoned"].asInt()))
        assertEquals("SUCCESS", days[1]["bestStatus"].asString())
        assertEquals("V07", days[1]["bestLabel"].asString())
        assertEquals(false, days[0]["covered"].asBoolean(), "today is not over")
        assertEquals("PENDING", days[0]["bestStatus"].asString())
        assertEquals(AutomationDaySeed.local("2026-11-11", "12:00").toString(), days[1]["from"].asString())
        assertEquals(AutomationDaySeed.local("2026-11-12", "12:00").toString(), days[1]["to"].asString())
        assertEquals(14, ok(get("/api/automation/days"))["items"].size())
    }

    @Test
    fun `a day's detail has its sessions, items, coverage and rows`() {
        seed.week()
        coverFrom("2026-11-01")

        val body = ok(get("/api/automation/days/2026-11-11"))

        assertEquals("2026-11-11", body["summary"]["day"].asString())
        assertEquals(true, body["coverage"]["covered"].asBoolean())
        assertEquals(1, body["coverage"]["ranges"].size())
        assertEquals(listOf("session-2026-11-11"), List(body["sessions"].size()) { body["sessions"][it]["id"].asString() })
        assertEquals("2026-11-11", body["sessions"][0]["tradingDay"].asString())
        assertEquals(2, body["items"].size())
        assertEquals(10, body["evaluations"].size())
        assertEquals(400, mvc.perform(get("/api/automation/days/not-a-date")).andReturn().response.status)
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
    fun `a session's detail lists its items oldest buy first and carries no evaluations`() {
        seedThreeNights()
        items.saveAll(listOf(item(1, "tonight", blockTime = EVENING.plusSeconds(20)), item(2, "tonight", blockTime = EVENING.plusSeconds(10))))

        val body = ok(get("/api/automation/sessions/tonight"))

        assertEquals("tonight", body["session"]["id"].asString())
        assertEquals("OPEN", body["session"]["state"].asString())
        assertEquals("2026-11-02", body["session"]["tradingDay"].asString())
        assertEquals(listOf("item-2", "item-1"), List(body["items"].size()) { body["items"][it]["id"].asString() })
        assertEquals("2000000000", body["items"][0]["curvePaymentLamports"].asString())
        assertEquals(false, body.has("evaluations"))
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
    fun `run now answers 202 and queues one pass, a second request answers 202 and queues nothing more`() {
        lookup.actions += buy(1, 100, EVENING.minusSeconds(60))

        val first = accepted(post("/api/automation/discovery/run"))
        val second = accepted(post("/api/automation/discovery/run"))

        assertEquals(listOf(true, false), listOf(first["queued"].asBoolean(), first["alreadyQueued"].asBoolean()))
        assertEquals(listOf(false, true), listOf(second["queued"].asBoolean(), second["alreadyQueued"].asBoolean()))
        assertEquals(1, scheduler.tasks.size)
        assertEquals(0, items.count(), "the request does not scan")
        assertEquals(true, ok(get("/api/automation/status"))["manualRunQueued"].asBoolean())
        scheduler.runNext()
        assertEquals(1, items.count())
        assertEquals(false, ok(get("/api/automation/status"))["manualRunQueued"].asBoolean())
    }

    @Test
    fun `run now with automation off is a 409 AUTOMATION_DISABLED`() {
        val off = AutomationProperties(enabled = false)
        val disabled = mvc(off, AutomationManualRun(off, runtime, null, null, clock))

        val response = disabled.perform(post("/api/automation/discovery/run")).andReturn().response

        assertEquals(409, response.status, response.contentAsString)
        assertEquals("AUTOMATION_DISABLED", body(response)["code"].asString())
    }

    @Test
    fun `status reports the settings, the mode, the coverage, the watermark, the day's requests against the cap and the open session`() {
        seedThreeNights()
        watermarks.save(AutomationWatermarkEntity(WALLET, 123, "sig-1", 2, -1, EVENING).apply { coveredFrom = EVENING.minus(Duration.ofHours(12)) })

        val body = ok(get("/api/automation/status"))

        assertEquals(true, body["enabled"].asBoolean())
        assertEquals(false, body["captureEnabled"].asBoolean())
        assertEquals(WALLET, body["wallet"].asString())
        assertEquals("+05:00", body["zone"].asString())
        assertEquals("WINDOW", body["mode"].asString())
        assertEquals(true, body["inPollWindow"].asBoolean())
        assertEquals(false, body["manualRunQueued"].asBoolean())
        assertEquals("12:00", body["tradingDayStart"].asString())
        assertEquals(EVENING.minus(Duration.ofHours(12)).toString(), body["coveredFrom"].asString())
        assertEquals(123, body["watermark"]["slot"].asLong())
        assertEquals(42, body["requestsLast24h"].asLong())
        assertEquals(5000, body["maxRequestsPer24h"].asLong())
        assertEquals("tonight", body["openSessionId"].asString())
        assertEquals("decu-sweep-1", body["sweepPlanId"].asString())
    }

    @Test
    fun `status in always-on mode scans at any hour, and without a watermark has no coverage`() {
        val always = properties(pollAlways = true)
        clock.now = Instant.parse("2026-11-02T07:00:00Z")

        val body = json.readTree(mvc(always, AutomationManualRun(always, runtime, null, null, clock)).perform(get("/api/automation/status")).andReturn().response.contentAsString)

        assertEquals("ALWAYS", body["mode"].asString())
        assertEquals(true, body["inPollWindow"].asBoolean())
        assertEquals(true, body["coveredFrom"].isNull)
    }

    @Test
    fun `creating a backfill answers 201 with it queued, and the same request id answers with the stored one`() {
        cover()
        val capturing = capturingMvc()

        val created = created(capturing, backfillRequest("req-1"))
        val again = created(capturing, backfillRequest("req-1", fromDay = "2026-10-20", toDay = "2026-10-21"))

        assertEquals("QUEUED", created["state"].asString())
        assertEquals(AutomationIds.backfill(WALLET, "req-1"), created["id"].asString())
        assertEquals("req-1", created["requestId"].asString())
        assertEquals("2026-10-29", created["fromDay"].asString())
        assertEquals("2026-10-30", created["toDay"].asString())
        assertEquals("2026-10-29T07:00:00Z", created["rangeStart"].asString())
        assertEquals("2026-10-31T07:00:00Z", created["rangeEnd"].asString())
        assertEquals(false, created["dryRun"].asBoolean())
        assertEquals(5000, created["requestBudget"].asLong())
        assertEquals(0, created["requestsSpent"].asLong())
        assertEquals(0, created["itemCounts"]["DISCOVERED"].asInt())
        assertEquals(true, created["minSignalLamports"].isNull)
        assertEquals(true, created["summary"].isNull)
        assertEquals(created["id"].asString(), again["id"].asString())
        assertEquals(1, backfills.count())
    }

    @Test
    fun `a backfill with filters echoes them`() {
        cover()

        val created = created(capturingMvc(), backfillRequest("req-1", extra = ""","minSignalSol":2.5,"mints":["${mint(1).value}","${mint(2).value}"]"""))

        assertEquals("2500000000", created["minSignalLamports"].asString())
        assertEquals(listOf(mint(1).value, mint(2).value), List(created["mints"].size()) { created["mints"][it].asString() })
    }

    @Test
    fun `an invalid backfill is a 400 naming the field, whether the value or the shape is wrong`() {
        cover()
        val capturing = capturingMvc()

        listOf(
            backfillRequest("req-1", budget = "99") to "requestBudget",
            backfillRequest("req-1", toDay = "2026-11-02") to "toDay",
            backfillRequest("req-1", extra = ""","minSignalSol":1.4""") to "minSignalSol",
            backfillRequest("req-1", extra = ""","mints":[]""") to "mints",
            backfillRequest("not valid!") to "requestId",
        ).forEach { (request, field) ->
            val response = capturing.perform(request).andReturn().response

            assertEquals(400, response.status, response.contentAsString)
            assertEquals("INVALID_REQUEST", body(response)["code"].asString())
            assertEquals(true, body(response)["fields"].has(field), "$field in ${response.contentAsString}")
        }
        val unreadable = capturing.perform(post("/api/automation/backfills").contentType(MediaType.APPLICATION_JSON).content("""{"fromDay":"yesterday"}""")).andReturn().response
        assertEquals(400, unreadable.status, unreadable.contentAsString)
        assertEquals(0, backfills.count())
    }

    @Test
    fun `a capturing backfill meeting live coverage is a 409, a second active one too, and capture off too`() {
        cover()
        val capturing = capturingMvc()

        val overlaps = capturing.perform(backfillRequest("req-1", fromDay = "2026-11-01", toDay = "2026-11-01")).andReturn().response
        created(capturing, backfillRequest("req-2"))
        val active = capturing.perform(backfillRequest("req-3", fromDay = "2026-10-20", toDay = "2026-10-21")).andReturn().response
        val noCapture = mvc.perform(backfillRequest("req-4")).andReturn().response

        assertEquals(409, overlaps.status, overlaps.contentAsString)
        assertEquals("BACKFILL_OVERLAPS_COVERAGE", body(overlaps)["code"].asString())
        assertEquals(409, active.status, active.contentAsString)
        assertEquals("BACKFILL_ACTIVE", body(active)["code"].asString())
        assertEquals(409, noCapture.status, noCapture.contentAsString)
        assertEquals("CAPTURE_DISABLED", body(noCapture)["code"].asString())
    }

    @Test
    fun `a dry run is created without capture, and backfills list newest first and read by id with their dry run summary`() {
        cover()
        val first = created(mvc, backfillRequest("req-1", dryRun = true))["id"].asString()
        lookup.actions += buy(1, Instant.parse("2026-10-30T14:00:00Z").epochSecond, Instant.parse("2026-10-30T14:00:00Z"))
        backfillDiscovery(lookup, properties, clock).scan()
        clock.now = EVENING.plusSeconds(60)
        val second = created(mvc, backfillRequest("req-2", dryRun = true))["id"].asString()

        val page = ok(get("/api/automation/backfills"))
        val detail = ok(get("/api/automation/backfills/$first"))

        assertEquals(listOf(second, first), ids(page))
        assertEquals(2, page["total"].asInt())
        assertEquals("DONE", detail["state"].asString())
        assertEquals(1, detail["buysPassing"].asInt())
        assertEquals(1, detail["summary"]["distinctMints"].asInt())
        assertEquals(1, detail["summary"]["buysPerDay"]["2026-10-30"].asInt())
        assertEquals(true, detail["estimatedRequests"].isNull)
        assertEquals(1, detail["discoveryRequests"].asLong())
        assertEquals(0, detail["skippedNoBlockTime"].asInt())
        assertEquals(false, detail["filtered"].asBoolean())
        assertEquals("2026-10-29T07:00:00Z", detail["scannedTo"].asString(), "the scan reached the range start")
        val unknown = mvc.perform(get("/api/automation/backfills/unknown")).andReturn().response
        assertEquals(404, unknown.status)
        assertEquals("AUTOMATION_BACKFILL_NOT_FOUND", body(unknown)["code"].asString())
    }

    @Test
    fun `cancelling a backfill answers it cancelled, again answers the same, and needs a request id`() {
        cover()
        val id = created(capturingMvc(), backfillRequest("req-1"))["id"].asString()

        val cancelled = ok(cancelRequest(id, "cancel-1"))
        val again = ok(cancelRequest(id, "cancel-1"))
        val missing = mvc.perform(post("/api/automation/backfills/$id/cancel").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response
        val unknown = mvc.perform(cancelRequest("unknown", "cancel-1")).andReturn().response

        assertEquals("CANCELLED", cancelled["state"].asString())
        assertEquals(cancelled, again)
        assertEquals(400, missing.status, missing.contentAsString)
        assertEquals(404, unknown.status, unknown.contentAsString)
    }

    @Test
    fun `status names the active backfill, and sessions carry their backfill`() {
        cover()
        val id = created(capturingMvc(), backfillRequest("req-1"))["id"].asString()
        sessions.save(session("backfilled", AutomationSessionState.CLOSING, Instant.parse("2026-10-30T14:00:00Z")).also { it.backfillId = id })
        sessions.save(session("live", AutomationSessionState.OPEN, EVENING))

        val status = ok(get("/api/automation/status"))
        val listed = ok(get("/api/automation/sessions"))
        val byId = List(listed["items"].size()) { listed["items"][it] }.associateBy { it["id"].asString() }

        assertEquals(id, status["activeBackfillId"].asString())
        assertEquals(id, byId.getValue("backfilled")["backfillId"].asString())
        assertEquals(true, byId.getValue("live")["backfillId"].isNull)
    }

    @Test
    fun `status has no active backfill without one`() {
        assertEquals(true, ok(get("/api/automation/status"))["activeBackfillId"].isNull)
    }

    /** Live discovery has covered the wallet from the trading day of 31 October. */
    private fun cover() {
        watermarks.save(AutomationWatermarkEntity(WALLET, 1, "sig-0", 2, -1, EVENING).apply { coveredFrom = Instant.parse("2026-10-31T07:00:00Z") })
    }

    private fun capturingMvc(): MockMvc {
        val capturing = properties(captureEnabled = true)
        return mvc(capturing, AutomationManualRun(capturing, runtime, discovery(lookup, capturing, clock), scheduler, clock))
    }

    private fun backfillRequest(
        requestId: String,
        fromDay: String = "2026-10-29",
        toDay: String = "2026-10-30",
        dryRun: Boolean = false,
        budget: String = "5000",
        extra: String = "",
    ) = post("/api/automation/backfills").contentType(MediaType.APPLICATION_JSON)
        .content("""{"requestId":"$requestId","fromDay":"$fromDay","toDay":"$toDay","dryRun":$dryRun,"requestBudget":$budget$extra}""")

    private fun cancelRequest(id: String, requestId: String) =
        post("/api/automation/backfills/$id/cancel").contentType(MediaType.APPLICATION_JSON).content("""{"requestId":"$requestId"}""")

    private fun created(on: MockMvc, request: RequestBuilder): JsonNode {
        val response = on.perform(request).andReturn().response
        assertEquals(201, response.status, response.contentAsString)
        return body(response)
    }

    private fun seedThreeNights() {
        sessions.saveAll(listOf(
            session("two-nights-ago", AutomationSessionState.CLOSED, EVENING.minus(Duration.ofDays(2))),
            session("yesterday", AutomationSessionState.CLOSED, EVENING.minus(Duration.ofDays(1))),
            session("tonight", AutomationSessionState.OPEN, EVENING),
        ))
    }

    /** Live discovery has covered the wallet from [date] at [time] local up to a scan that started at 13:00 on 12 November. */
    private fun coverFrom(date: String, time: String = "12:00") {
        clock.now = AutomationDaySeed.local("2026-11-12", "13:00")
        watermarks.save((watermarks.findById(WALLET).orElse(null) ?: AutomationWatermarkEntity(WALLET, 1, "sig-1", 2, -1, clock.now)).apply {
            updatedAt = clock.now
            coveredFrom = AutomationDaySeed.local(date, time)
        })
    }

    private fun closeRequest(id: String, requestId: String) =
        post("/api/automation/sessions/$id/close").contentType(MediaType.APPLICATION_JSON).content("""{"requestId":"$requestId"}""")

    private fun ok(request: RequestBuilder): JsonNode = respond(request, 200)

    private fun accepted(request: RequestBuilder): JsonNode = respond(request, 202)

    private fun respond(request: RequestBuilder, status: Int): JsonNode {
        val response = mvc.perform(request).andReturn().response
        assertEquals(status, response.status, response.contentAsString)
        return body(response)
    }

    private fun body(response: MockHttpServletResponse): JsonNode = json.readTree(response.contentAsString)

    private fun ids(body: JsonNode): List<String> = List(body["items"].size()) { body["items"][it]["id"].asString() }

    private fun runKeys(body: JsonNode): List<String> =
        List(body["items"].size()) { body["items"][it]["itemId"].asString() + ":" + body["items"][it]["label"].asString() }

}
