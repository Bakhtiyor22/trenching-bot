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
import com.trenching.bot.console.automation.AutomationVariationRows
import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.AutomationItemEventKind
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.automation.domain.BackfillState
import com.trenching.bot.console.automation.domain.SweepPlans
import com.trenching.bot.console.automation.domain.VariationEditRules
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEventEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkEntity
import com.trenching.bot.console.automation.mapper.AutomationBackfillRequestMapperImpl
import com.trenching.bot.console.automation.mapper.AutomationResponseMapperImpl
import com.trenching.bot.console.automation.mapper.AutomationVariationResponseMapperImpl
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.console.mapper.ConfigResponseMapperImpl
import com.trenching.bot.console.mapper.ConsoleErrorMapperImpl
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.util.ReflectionTestUtils
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

    private val variationResponses = AutomationVariationResponseMapperImpl().also { ReflectionTestUtils.setField(it, "configResponseMapper", ConfigResponseMapperImpl()) }

    private fun mvc(properties: AutomationProperties, manualRun: AutomationManualRun): MockMvc =
        MockMvcBuilders.standaloneSetup(AutomationController(query(properties, clock, usage), dayService(properties, clock), sessionService(properties, clock),
            manualRun, backfillService(properties, clock), backfillQuery(properties), retryService(properties, clock), AutomationResponseMapperImpl(), AutomationBackfillRequestMapperImpl(),
            variations(properties, clock), variationResponses))
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
    fun `sweep runs filter by plan and by the buy's block time, and a buy without one drops out when a bound is given`() {
        seedThreeNights()
        items.saveAll(listOf(
            item(1, "yesterday", AutomationItemState.SIMULATED, blockTime = EVENING.minus(Duration.ofDays(1))),
            item(2, "tonight", AutomationItemState.SIMULATED, blockTime = EVENING),
            item(3, "tonight", AutomationItemState.SIMULATED, blockTime = null),
        ))
        sweepRuns.saveAll(listOf(
            sweepRun("r1", "item-1", "V04", sessionId = "yesterday"),
            sweepRun("r2", "item-2", "V04", sessionId = "tonight"),
            sweepRun("r2-other", "item-2", "V04", sessionId = "tonight").apply { planId = "other-plan" },
            sweepRun("r2-v05", "item-2", "V05", sessionId = "tonight"),
            sweepRun("r3", "item-3", "V04", sessionId = "tonight"),
        ))
        val range = "from=${EVENING.minusSeconds(60)}&to=${EVENING.plusSeconds(60)}"

        val inRange = ok(get("/api/automation/sweep-runs?label=V04&$range&page=0&size=50"))

        assertEquals(2, inRange["total"].asInt())
        assertEquals(listOf(mint(2).value, mint(2).value), List(2) { inRange["items"][it]["mint"].asString() })
        assertEquals(listOf("item-2:V04"), runKeys(ok(get("/api/automation/sweep-runs?label=V04&planId=decu-sweep-1&$range"))))
        assertEquals(listOf("other-plan"), ok(get("/api/automation/sweep-runs?label=V04&planId=other-plan"))["items"].let { runs -> List(runs.size()) { runs[it]["planId"].asString() } })
        assertEquals(4, ok(get("/api/automation/sweep-runs?label=V04"))["total"].asInt(), "a buy without a block time is listed when no bound is given")
        assertEquals(2, ok(get("/api/automation/sweep-runs?label=V04&from=${EVENING}"))["total"].asInt(), "from is inclusive and drops the buy without a block time")
        assertEquals(3, ok(get("/api/automation/sweep-runs?label=V04&to=${EVENING.plusSeconds(1)}"))["total"].asInt())
        assertEquals(1, ok(get("/api/automation/sweep-runs?label=V04&to=${EVENING}"))["total"].asInt(), "to is exclusive and drops the buy without a block time")
    }

    @Test
    fun `sweep runs refuse a from that is not before to`() {
        val response = mvc.perform(get("/api/automation/sweep-runs?from=${EVENING}&to=${EVENING}")).andReturn().response

        assertEquals(400, response.status, response.contentAsString)
        assertEquals("INVALID_REQUEST", body(response)["code"].asString())
        assertEquals(true, body(response)["fields"].has("from"))
        assertEquals(400, mvc.perform(get("/api/automation/sweep-runs?from=yesterday")).andReturn().response.status)
    }

    @Test
    fun `variations list by sequence with their configuration and what differs from the agreed one, filtered by state`() {
        val body = ok(get("/api/automation/variations"))
        val items = List(body["items"].size()) { body["items"][it] }

        assertEquals("decu-sweep-1", body["planId"].asString())
        assertEquals((0..9).map { "V0$it" }, items.map { it["label"].asString() })
        assertEquals(List(10) { "ACTIVE" }, items.map { it["state"].asString() })
        assertEquals(0, items[0]["differsFromAgreed"].size())
        assertEquals(listOf("takeProfitBps"), List(items[4]["differsFromAgreed"].size()) { items[4]["differsFromAgreed"][it].asString() })
        assertEquals(0, items[4]["changedFromReplaced"].size())
        assertEquals(listOf(8000, "80", "0.100000000000"), listOf(items[4]["config"]["takeProfitBps"].asInt(), items[4]["config"]["takeProfitPercent"].asString(),
            items[4]["config"]["notional"].asString()))
        assertEquals(true, items[4]["activeFrom"].isNull)
        assertEquals(true, items[4]["archivedAt"].isNull)
        assertEquals(10, ok(get("/api/automation/variations?state=ACTIVE"))["items"].size())
        assertEquals(0, ok(get("/api/automation/variations?state=ARCHIVED"))["items"].size())
    }

    @Test
    fun `an unknown state is a 400 naming state, and an unknown variation is a 404`() {
        val bad = mvc.perform(get("/api/automation/variations?state=active")).andReturn().response
        val unknown = mvc.perform(get("/api/automation/variations/V99")).andReturn().response

        assertEquals(400, bad.status, bad.contentAsString)
        assertEquals("INVALID_REQUEST", body(bad)["code"].asString())
        assertEquals(true, body(bad)["fields"].has("state"))
        assertEquals(404, unknown.status, unknown.contentAsString)
        assertEquals("AUTOMATION_VARIATION_NOT_FOUND", body(unknown)["code"].asString())
    }

    @Test
    fun `editing V04 answers 201 with V10, archives V04, and the same request answers 200 with V10 and stores nothing more`() {
        val created = edited("V04", "req-1")
        val repeated = ok(editRequest("V04", "req-1", editConfig("80", "50")))
        val old = ok(get("/api/automation/variations/V04"))

        assertEquals(listOf("V10", "ACTIVE", 8000, 5000, "V04"), listOf(created["label"].asString(), created["state"].asString(),
            created["config"]["takeProfitBps"].asInt(), created["config"]["stopLossBps"].asInt(), created["replacesLabel"].asString()))
        assertEquals(listOf("stopLossBps"), List(created["changedFromReplaced"].size()) { created["changedFromReplaced"][it].asString() })
        assertEquals(clock.instant().toString(), created["activeFrom"].asString())
        assertEquals("V10", repeated["label"].asString())
        assertEquals(created, repeated)
        assertEquals(listOf("ARCHIVED", clock.instant().toString(), "V10"), listOf(old["state"].asString(), old["archivedAt"].asString(), old["replacedByLabel"].asString()))
        assertEquals(11, ok(get("/api/automation/variations"))["items"].size())
        assertEquals(listOf("V04"), ok(get("/api/automation/variations?state=ARCHIVED"))["items"].let { list -> List(list.size()) { list[it]["label"].asString() } })
    }

    @Test
    fun `a refused edit answers its status and code, with the field the console places the reason on`() {
        edited("V04", "req-1")
        val ownConfig = editConfig("80", "40")

        val baseline = mvc.perform(editRequest("V00", "req-2", editConfig("80", "50"))).andReturn().response
        val archived = mvc.perform(editRequest("V04", "req-3", editConfig("70", "50"))).andReturn().response
        val unchanged = mvc.perform(editRequest("V04", "req-4", ownConfig)).andReturn().response
        val unchangedActive = mvc.perform(editRequest("V03", "req-5", editConfig("35", "40", dip = "20"))).andReturn().response
        val duplicate = mvc.perform(editRequest("V05", "req-6", editConfig("80", "50"))).andReturn().response
        val unknown = mvc.perform(editRequest("V99", "req-7", ownConfig)).andReturn().response

        assertEquals(listOf(409, 409, 409, 409, 409, 404), listOf(baseline, archived, unchanged, unchangedActive, duplicate, unknown).map { it.status })
        assertEquals("VARIATION_BASELINE", body(baseline)["code"].asString())
        assertEquals("VARIATION_ARCHIVED", body(archived)["code"].asString())
        assertEquals("VARIATION_ARCHIVED", body(unchanged)["code"].asString(), "an archived variation is refused before its configuration is compared")
        assertEquals("VARIATION_UNCHANGED", body(unchangedActive)["code"].asString())
        assertEquals("VARIATION_DUPLICATE", body(duplicate)["code"].asString())
        assertEquals("V10", body(duplicate)["fields"]["label"].asString())
        assertEquals("AUTOMATION_VARIATION_NOT_FOUND", body(unknown)["code"].asString())
        assertEquals(11, ok(get("/api/automation/variations"))["items"].size())
    }

    @Test
    fun `an edit that would pass the bound of a plan is a 409 with its own code, and stores nothing`() {
        val last = AutomationLimits.MAX_VARIATIONS_PER_PLAN - 1
        variationRows.saveAndFlush(AutomationVariationRows.entityLike(
            checkNotNull(variationRows.findOneByPlanIdAndLabel(SweepPlans.DECU_SWEEP_1.id, "V05")), label = VariationEditRules.labelOf(last), seq = last,
        ))

        val refused = mvc.perform(editRequest("V04", "req-1", editConfig("80", "50"))).andReturn().response

        assertEquals(409, refused.status)
        assertEquals("VARIATION_LIMIT_REACHED", body(refused)["code"].asString())
        assertEquals(11, variationRows.count())
    }

    @Test
    fun `an edit with a missing request id, a missing field or a bad setting is a 400 naming its fields`() {
        val noRequestId = mvc.perform(post("/api/automation/variations/V04/edit").contentType(MediaType.APPLICATION_JSON).content("""{"config":${editConfig("80", "50")}}""")).andReturn().response
        val noConfig = mvc.perform(post("/api/automation/variations/V04/edit").contentType(MediaType.APPLICATION_JSON).content("""{"requestId":"req-1"}""")).andReturn().response
        val missingField = mvc.perform(editRequest("V04", "req-2", editConfig("80", "50").replace("\"latencySlots\":1,", ""))).andReturn().response
        val badPercent = mvc.perform(editRequest("V04", "req-3", editConfig("80.555", "50"))).andReturn().response
        val lowSignal = mvc.perform(editRequest("V04", "req-4", editConfig("80", "50").replace("\"1.9\"", "\"1.4\""))).andReturn().response

        assertEquals(listOf(400, 400, 400, 400, 400), listOf(noRequestId, noConfig, missingField, badPercent, lowSignal).map { it.status })
        assertEquals("INVALID_REQUEST", body(noRequestId)["code"].asString())
        assertEquals(true, body(noRequestId)["fields"].has("requestId"))
        assertEquals("CONFIG_INVALID", body(noConfig)["code"].asString())
        assertEquals(16, body(noConfig)["fields"].size())
        assertEquals(listOf("CONFIG_INVALID", "latencySlots"), listOf(body(missingField)["code"].asString(), body(missingField)["fields"].propertyNames().single()))
        assertEquals(listOf("INVALID_PERCENT", "takeProfitPercent"), listOf(body(badPercent)["code"].asString(), body(badPercent)["fields"].propertyNames().single()))
        assertEquals(true, body(lowSignal)["fields"].has("minSignalSol"))
        assertEquals(10, ok(get("/api/automation/variations"))["items"].size())
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
    fun `an item's events are listed in seq order with every field, and an item without any lists none`() {
        items.saveAll(listOf(item(1, "tonight", AutomationItemState.FAILED), item(2, "tonight", AutomationItemState.DISCOVERED)))
        itemEvents.saveAll(listOf(
            event("item-1", 2, AutomationItemEventKind.SWEEP_ENTRY_RECORDED, EVENING.plusSeconds(60), step = AutomationStep.SWEEP, attempt = 1,
                detail = """{"planId":"decu-sweep-1","label":"V00","runId":"run-1","outcome":"STOP_LOSS"}"""),
            event("item-1", 3, AutomationItemEventKind.ATTEMPT_FAILED, EVENING.plusSeconds(120), toState = null, step = AutomationStep.RATES, attempt = 2,
                failureCode = "RATES_INCOMPLETE", failureMessage = "rates were incomplete", operationId = "op-1", captureSessionId = "capture-1"),
            event("item-1", 1, AutomationItemEventKind.STATE_CHANGED, EVENING, toState = AutomationItemState.DISCOVERED),
        ))

        val body = ok(get("/api/automation/items/item-1/events"))

        assertEquals("item-1", body["itemId"].asString())
        val events = body["events"]
        assertEquals(listOf(1, 2, 3), List(events.size()) { events[it]["seq"].asInt() })
        assertEquals("STATE_CHANGED", events[0]["kind"].asString())
        assertEquals(true, events[0]["fromState"].isNull)
        assertEquals("DISCOVERED", events[0]["toState"].asString())
        assertEquals(EVENING.toString(), events[0]["at"].asString())
        assertEquals(true, events[0]["detail"].isNull)
        assertEquals("SWEEP", events[1]["step"].asString())
        assertEquals("run-1", events[1]["detail"]["runId"].asString())
        assertEquals("STOP_LOSS", events[1]["detail"]["outcome"].asString())
        with(events[2]) {
            assertEquals("ATTEMPT_FAILED", this["kind"].asString())
            assertEquals(2, this["attempt"].asInt())
            assertEquals("RATES_INCOMPLETE", this["failureCode"].asString())
            assertEquals("rates were incomplete", this["failureMessage"].asString())
            assertEquals("op-1", this["operationId"].asString())
            assertEquals("capture-1", this["captureSessionId"].asString())
            assertEquals(true, this["actor"].isNull)
            assertEquals(true, this["requestId"].isNull)
            assertEquals(true, this["note"].isNull)
            assertEquals(true, this["requestsUsed"].isNull)
        }
        assertEquals(0, ok(get("/api/automation/items/item-2/events"))["events"].size())
    }

    @Test
    fun `the events of an unknown item are a 404`() {
        val response = mvc.perform(get("/api/automation/items/missing/events")).andReturn().response

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
    fun `retrying a failed item answers 200 with the item back in capture and its retry count, and the item read says whether it can be retried`() {
        seedThreeNights()
        items.saveAll(listOf(failedItem(1, "tonight", "RATES_INCOMPLETE"), failedItem(2, "tonight", "BACKFILL_BUDGET")))

        val before = ok(get("/api/automation/items/item-1"), capturing)["item"]
        val blocked = ok(get("/api/automation/items/item-2"), capturing)["item"]
        val retried = ok(retryRequest("/api/automation/items/item-1/retry", "retry-1"), capturing)["item"]

        assertEquals(true, before["retryRefusal"].isNull)
        assertEquals(0, before["retries"].asInt())
        assertEquals("RETRY_NOT_ALLOWED", blocked["retryRefusal"].asString())
        assertEquals(listOf("CAPTURING", "RATES", 1, 1), listOf(retried["state"].asString(), retried["step"].asString(), retried["stepAttempt"].asInt(), retried["retries"].asInt()))
        assertEquals(true, retried["failureCode"].isNull)
        assertEquals("ITEM_NOT_FAILED", retried["retryRefusal"].asString())
    }

    @Test
    fun `a retry that is refused answers 409 with the refusal code, and the failure code for an item that cannot be retried`() {
        seedThreeNights()
        items.saveAll(listOf(failedItem(1, "tonight", "BACKFILL_BUDGET"), item(2, "tonight", AutomationItemState.ABANDONED), failedItem(3, "tonight", "X")))

        val notAllowed = conflict(retryRequest("/api/automation/items/item-1/retry", "retry-1"), capturing)
        val notFailed = conflict(retryRequest("/api/automation/items/item-2/retry", "retry-2"), capturing)
        val disabled = conflict(retryRequest("/api/automation/items/item-3/retry", "retry-3"), mvc)

        assertEquals("RETRY_NOT_ALLOWED", notAllowed["code"].asString())
        assertEquals("BACKFILL_BUDGET", notAllowed["fields"]["failureCode"].asString())
        assertEquals("ITEM_NOT_FAILED", notFailed["code"].asString())
        assertEquals("CAPTURE_DISABLED", disabled["code"].asString())
    }

    @Test
    fun `a retry of an item whose backfill is still scanning answers 409 BACKFILL_NOT_CAPTURING`() {
        seedThreeNights()
        backfills.save(AutomationBackfillEntity("bf-1", "req-1", WALLET, null, LocalDate.parse("2026-10-29"), LocalDate.parse("2026-10-30"), EVENING,
            EVENING.plusSeconds(60), false, null, null, 20_000, BackfillState.SCANNING, EVENING, EVENING))
        items.save(failedItem(1, "tonight", "X").apply { backfillId = "bf-1" })

        val refused = conflict(retryRequest("/api/automation/items/item-1/retry", "retry-1"), capturing)

        assertEquals("BACKFILL_NOT_CAPTURING", refused["code"].asString())
    }

    @Test
    fun `a retry without a valid request id or with a malformed date is a 400, and an unknown item is a 404`() {
        seedThreeNights()
        items.save(failedItem(1, "tonight", "X"))

        val missing = capturing.perform(post("/api/automation/items/item-1/retry").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response
        val malformed = capturing.perform(post("/api/automation/items/item-1/retry").contentType(MediaType.APPLICATION_JSON).content("""{"requestId":"no good"}""")).andReturn().response
        val badDate = capturing.perform(post("/api/automation/days/yesterday/retry").contentType(MediaType.APPLICATION_JSON).content("""{"requestId":"day-1"}""")).andReturn().response
        val unknown = capturing.perform(post("/api/automation/items/nope/retry").contentType(MediaType.APPLICATION_JSON).content("""{"requestId":"retry-1"}""")).andReturn().response

        assertEquals(listOf(400, 400, 400, 404), listOf(missing.status, malformed.status, badDate.status, unknown.status))
        assertEquals("INVALID_REQUEST", body(missing)["code"].asString())
        assertEquals("AUTOMATION_ITEM_NOT_FOUND", body(unknown)["code"].asString())
    }

    @Test
    fun `retrying a day answers 200 with the retried item ids and the refusal code of each other failed item`() {
        seedThreeNights()
        items.saveAll(listOf(failedItem(1, "tonight", "RATES_INCOMPLETE"), failedItem(2, "tonight", "BACKFILL_BUDGET"), item(3, "tonight", AutomationItemState.SIMULATED)))

        val result = ok(retryRequest("/api/automation/days/2026-11-02/retry", "day-1"), capturing)

        assertEquals("2026-11-02", result["day"].asString())
        assertEquals(listOf("item-1"), List(result["retried"].size()) { result["retried"][it].asString() })
        assertEquals("RETRY_NOT_ALLOWED", result["refused"]["item-2"].asString())
        assertEquals(1, result["refused"].size())
        assertEquals(0, result["unchanged"].size())
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
        assertEquals(7500, body["maxRequestsPer24h"].asLong())
        assertEquals("tonight", body["openSessionId"].asString())
        assertEquals("decu-sweep-1", body["sweepPlanId"].asString())
        assertEquals("1.5", body["captureMinSignalSol"].asString(), "the capture filter, the least minimum signal size a variation may have")
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

    /** The agreed configuration as the console's edit form sends it: every field, the levels as percents. */
    private fun editConfig(takeProfit: String, stopLoss: String, dip: String = "20") =
        """{"notional":"0.1","maxAgeSlots":2,"dipPercent":"$dip","takeProfitPercent":"$takeProfit","stopLossPercent":"$stopLoss","minMarketCapUsd":"10000",""" +
            """"horizonSlots":4500,"latencySlots":1,"transactionCostSol":"0.001","quoteMint":"So11111111111111111111111111111111111111112",""" +
            """"dipWindowMs":30000,"maxHoldMs":180000,"hardExitStartMs":240000,"priceMeasure":"POST_TRADE","followWalletSells":true,"minSignalSol":"1.9"}"""

    private fun editRequest(label: String, requestId: String, config: String) =
        post("/api/automation/variations/$label/edit").contentType(MediaType.APPLICATION_JSON).content("""{"requestId":"$requestId","config":$config}""")

    private fun edited(label: String, requestId: String): JsonNode = created(mvc, editRequest(label, requestId, editConfig("80", "50")))

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

    private val capturing: MockMvc by lazy {
        val captureProperties = properties(captureEnabled = true)
        mvc(captureProperties, AutomationManualRun(captureProperties, runtime, discovery(lookup, captureProperties, clock), scheduler, clock))
    }

    private fun failedItem(n: Int, sessionId: String, code: String) =
        item(n, sessionId, AutomationItemState.FAILED).apply {
            failureCode = code
            step = AutomationStep.RATES
            stepAttempt = 2
            captureSessionId = "capture-$n"
        }

    private fun event(
        itemId: String,
        seq: Int,
        kind: AutomationItemEventKind,
        at: Instant,
        toState: AutomationItemState? = null,
        step: AutomationStep? = null,
        attempt: Int? = null,
        failureCode: String? = null,
        failureMessage: String? = null,
        operationId: String? = null,
        captureSessionId: String? = null,
        detail: String? = null,
    ) = AutomationItemEventEntity(AutomationIds.itemEvent(itemId, seq), itemId, seq, at, kind, null, toState, step, attempt, captureSessionId, operationId,
        null, failureCode, failureMessage, null, null, null, detail)

    private fun retryRequest(path: String, requestId: String) =
        post(path).contentType(MediaType.APPLICATION_JSON).content("""{"requestId":"$requestId"}""")

    private fun conflict(request: RequestBuilder, on: MockMvc = mvc): JsonNode = respond(request, 409, on)

    private fun ok(request: RequestBuilder, on: MockMvc = mvc): JsonNode = respond(request, 200, on)

    private fun accepted(request: RequestBuilder): JsonNode = respond(request, 202)

    private fun respond(request: RequestBuilder, status: Int, on: MockMvc = mvc): JsonNode {
        val response = on.perform(request).andReturn().response
        assertEquals(status, response.status, response.contentAsString)
        return body(response)
    }

    private fun body(response: MockHttpServletResponse): JsonNode = json.readTree(response.contentAsString)

    private fun ids(body: JsonNode): List<String> = List(body["items"].size()) { body["items"][it]["id"].asString() }

    private fun runKeys(body: JsonNode): List<String> =
        List(body["items"].size()) { body["items"][it]["itemId"].asString() + ":" + body["items"][it]["label"].asString() }

}
