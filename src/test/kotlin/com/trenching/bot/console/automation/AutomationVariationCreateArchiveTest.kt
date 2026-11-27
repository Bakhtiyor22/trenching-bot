package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationDaySeed.Companion.local
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.api.dto.AutomationVariationConfigRequest
import com.trenching.bot.console.application.ConsoleConfigValidator
import com.trenching.bot.console.automation.application.AutomationVariationService
import com.trenching.bot.console.automation.application.AutomationVariationTransaction
import com.trenching.bot.console.automation.application.AutomationVariationViews
import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.AutomationItemEventKind
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.InvalidAutomationRequestException
import com.trenching.bot.console.automation.domain.InvalidVariationConfigException
import com.trenching.bot.console.automation.domain.SweepPlans
import com.trenching.bot.console.automation.domain.VariationBaselineException
import com.trenching.bot.console.automation.domain.VariationDuplicateException
import com.trenching.bot.console.automation.domain.VariationEditRules
import com.trenching.bot.console.automation.domain.VariationLimitReachedException
import com.trenching.bot.console.automation.domain.VariationArchiveResult
import com.trenching.bot.console.automation.domain.VariationArchivedException
import com.trenching.bot.console.automation.domain.VariationNotFoundException
import com.trenching.bot.console.automation.domain.VariationRequestReusedException
import com.trenching.bot.console.automation.domain.VariationState
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEventEntity
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.strategy.domain.SimulationConfig
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

/** Variation versions plan Key Decisions 3 to 5 and Domain Rules 5 to 8: a new variation under the next base label, and an archive that never deletes. */
class AutomationVariationCreateArchiveTest : AutomationDatabaseTest() {
    private val clock = MutableClock(local("2026-11-26", "13:00"))
    private val plan = SweepPlans.DECU_SWEEP_1
    private val legacy by lazy { AutomationVariationRows(variationRows, variationMapper) }
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()

    @Test
    fun `a new variation takes the next base label, is active from now, replaces nothing and keeps its request id`() {
        val result = service().create(request("req-1", config("80", "50")))

        val created = result.variation
        assertTrue(result.created)
        assertEquals(listOf("V10", "V10", 10, VariationState.ACTIVE, null), listOf(created.variation.label, created.name.text, created.variation.seq,
            created.variation.state, created.variation.replacesLabel))
        assertEquals(listOf(clock.instant(), null, null), listOf(created.variation.activeFrom, created.variation.archivedAt, created.variation.replacedByLabel))
        assertEquals(listOf("takeProfitBps", "stopLossBps"), created.differsFromAgreed)
        assertEquals(emptyList(), created.changedFromReplaced)
        assertEquals(11, variationRows.count())
        assertEquals("req-1", variationRows.findOneByPlanIdAndLabel(plan.id, "V10")?.requestId)
        assertEquals(created.variation, service().findView("V10").variation)
        assertEquals(plan.entries.map { it.label } + "V10", service().activePlan().entries.map { it.label })
    }

    @Test
    fun `the next base label passes the labels of earlier edits, so V00 to V15 give V16 and a second new variation V17`() {
        listOf("V03", "V04", "V10", "V09", "V01", "V07").forEachIndexed { n, label -> legacy.edit(label, local("2026-11-26", "11:3$n"), "legacy-$n") }

        val first = service().create(request("req-1", config("82", "50")))
        val second = service().create(request("req-2", config("83", "50")))

        assertEquals(listOf("V16", 16, "V17", 17), listOf(first.variation.variation.label, first.variation.variation.seq, second.variation.variation.label, second.variation.variation.seq))
        assertEquals(listOf("V03.2", "V04.1"), listOf("V12", "V11").map { service().findView(it).name.text })
    }

    @Test
    fun `the same request id again answers the stored variation and writes nothing`() {
        val first = service().create(request("req-1", config("80", "50")))
        val before = rows()
        clock.now = clock.now.plusSeconds(60)

        val again = service().create(request("req-1", config("70", "50")))

        assertFalse(again.created)
        assertEquals(first.variation, again.variation)
        assertEquals(before, rows())
    }

    @Test
    fun `the configuration of an active variation is a duplicate naming it, and stores nothing`() {
        val before = rows()
        val own = body(plan.entries.single { it.label == "V05" }.config)

        val refused = assertFailsWith<VariationDuplicateException> { service().create(request("req-1", own)) }

        assertEquals("V05", refused.label)
        assertEquals(before, rows())
    }

    @Test
    fun `the configuration of an archived variation is accepted`() {
        service().archive("V05")

        val result = service().create(request("req-1", body(plan.entries.single { it.label == "V05" }.config)))

        assertTrue(result.created)
        assertEquals("V10", result.variation.variation.label)
    }

    @Test
    fun `a plan at its bound refuses a new variation and stores nothing`() {
        val last = AutomationLimits.MAX_VARIATIONS_PER_PLAN - 1
        variationRows.saveAndFlush(AutomationVariationRows.entityLike(
            checkNotNull(variationRows.findOneByPlanIdAndLabel(plan.id, "V05")), label = VariationEditRules.labelOf(last), seq = last,
        ))
        val before = rows()

        val refused = assertFailsWith<VariationLimitReachedException> { service().create(request("req-1", config("80", "50"))) }

        assertEquals(plan.id, refused.planId)
        assertEquals(before, rows())
    }

    @Test
    fun `a bad request id or configuration is refused with the edit's codes before anything is stored`() {
        val before = rows()

        listOf("", "  ", "x".repeat(65)).forEach { requestId ->
            assertEquals("requestId", assertFailsWith<InvalidAutomationRequestException> { service().create(request(requestId, config("80", "50"))) }.field)
        }
        val noConfig = assertFailsWith<InvalidVariationConfigException> { service().create(json.readValue<AutomationVariationConfigRequest>("""{"requestId":"req-1"}""")) }
        val badPercent = assertFailsWith<InvalidVariationConfigException> { service().create(request("req-2", config("80.555", "50"))) }

        assertEquals(16, noConfig.fields.size)
        assertEquals(listOf("takeProfitPercent"), badPercent.fields.keys.toList())
        assertEquals(before, rows())
        assertTrue(service().create(request("x".repeat(64), config("80", "50"))).created)
    }

    @Test
    fun `a request id an edit stored is refused for a creation, naming the id and the stored label, and stores nothing`() {
        service().edit("V04", request("req-1", config("80", "50")))
        val before = rows()

        val refused = assertFailsWith<VariationRequestReusedException> { service().create(request("req-1", config("60", "50"))) }

        assertEquals(listOf("req-1", "V04.1"), listOf(refused.requestId, refused.storedLabel))
        assertEquals(before, rows())
    }

    @Test
    fun `a request id a creation stored is refused for an edit, whichever label it edits, and stores nothing`() {
        service().create(request("req-1", config("80", "50")))
        val before = rows()

        val ofAnother = assertFailsWith<VariationRequestReusedException> { service().edit("V04", request("req-1", config("60", "50"))) }
        val ofItself = assertFailsWith<VariationRequestReusedException> { service().edit("V10", request("req-1", config("61", "50"))) }

        assertEquals(listOf("req-1", "V10"), listOf(ofAnother.requestId, ofAnother.storedLabel))
        assertEquals("V10", ofItself.storedLabel)
        assertEquals(before, rows())
    }

    @Test
    fun `a request id that another operation stored is refused on the race path too`() {
        service().edit("V04", request("req-1", config("80", "50")))
        val before = rows()
        val racing = object : AutomationVariationTransaction(variationRows, variationMapper, AutomationVariationViews(variationRows, variationMapper)) {
            private var first = true

            override fun existing(planId: String, requestId: String, replacesLabel: String?) =
                if (first) null.also { first = false } else super.existing(planId, requestId, replacesLabel)

            override fun create(planId: String, requestId: String, config: SimulationConfig, now: Instant) =
                throw DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_automation_variations_request\"")
        }

        assertFailsWith<VariationRequestReusedException> { variationsWith(racing).create(request("req-1", config("60", "50"))) }
        assertEquals(before, rows())
    }

    @Test
    fun `two creations at once, behind the plan's lock, are V10 and V11 with their own sequence numbers and no conflict`() {
        val outcomes = concurrentlyBehindLock(
            { service().create(request("req-a", config("80", "50"))) },
            { service().create(request("req-b", config("83", "50"))) },
        ).map { it.getOrThrow() }

        assertTrue(outcomes.all { it.created })
        assertEquals(listOf("V10", "V11"), outcomes.map { it.variation.variation.label }.sorted())
        assertEquals(listOf(10, 11), variationRows.findAll().filter { it.seq >= 10 }.map { it.seq }.sorted())
        assertEquals(12, variationRows.count())
    }

    @Test
    fun `a creation racing an edit of V04, behind the plan's lock, gives V04 point 1 and V10 with distinct sequence numbers`() {
        val outcomes = concurrentlyBehindLock(
            { service().create(request("req-a", config("83", "50"))) },
            { service().edit("V04", request("req-b", config("80", "50"))) },
        ).map { it.getOrThrow() }

        assertTrue(outcomes.all { it.created })
        assertEquals(listOf("V04.1", "V10"), outcomes.map { it.variation.variation.label }.sorted())
        assertEquals(setOf(10, 11), variationRows.findAll().filter { it.seq >= 10 }.map { it.seq }.toSet())
        assertEquals(12, variationRows.count())
        assertEquals(VariationState.ARCHIVED, service().findView("V04").variation.state)
    }

    @Test
    fun `an archive racing an edit of the same label, behind the plan's lock, ends with one outcome and no second replacement`() {
        val outcomes = concurrentlyBehindLock<Any>(
            { service().archive("V04") },
            { service().edit("V04", request("req-a", config("80", "50"))) },
        )

        val archive = outcomes[0]
        val edit = outcomes[1]
        val replacements = variationRows.findAll().filter { it.replacesLabel == "V04" }.map { it.label }
        assertEquals(VariationState.ARCHIVED, service().findView("V04").variation.state)
        if (edit.isSuccess) {
            assertEquals(listOf("V04.1"), replacements)
            assertEquals(false, (archive.getOrThrow() as VariationArchiveResult).archived, "the edit archived it first")
            assertEquals(11, variationRows.count())
        } else {
            assertTrue(edit.exceptionOrNull() is VariationArchivedException, "${edit.exceptionOrNull()}")
            assertEquals(true, (archive.getOrThrow() as VariationArchiveResult).archived)
            assertEquals(emptyList(), replacements)
            assertEquals(10, variationRows.count())
        }
    }

    @Test
    fun `a create that loses a race on a unique key is a conflict, not a stored row`() {
        val racing = object : AutomationVariationTransaction(variationRows, variationMapper, AutomationVariationViews(variationRows, variationMapper)) {
            override fun create(planId: String, requestId: String, config: SimulationConfig, now: Instant) =
                throw DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_automation_variations_label\"")
        }

        assertFailsWith<OptimisticLockingFailureException> {
            variationsWith(racing).create(request("req-1", config("80", "50")))
        }
        assertEquals(10, variationRows.count())
    }

    @Test
    fun `archiving sets the state and the time, leaves the replacement empty and keeps every row`() {
        clock.now = local("2026-11-26", "14:00")

        val result = service().archive("V05")

        val archived = result.variation
        assertTrue(result.archived)
        assertEquals(listOf(VariationState.ARCHIVED, clock.instant(), null, "V05"), listOf(archived.variation.state, archived.variation.archivedAt,
            archived.variation.replacedByLabel, archived.name.text))
        assertEquals(10, variationRows.count())
        assertEquals(archived.variation, service().findView("V05").variation)
        assertEquals(listOf("V05"), service().listViews("ARCHIVED").items.map { it.variation.label })
        assertEquals(plan.entries.map { it.label } - "V05", service().activePlan().entries.map { it.label })
    }

    @Test
    fun `archiving again, or archiving what an edit archived, answers the variation unchanged and writes nothing`() {
        service().archive("V05")
        service().edit("V04", request("req-1", config("80", "50")))
        val before = rows()
        val archivedAt = service().findView("V05").variation.archivedAt
        clock.now = clock.now.plusSeconds(3600)

        val again = service().archive("V05")
        val byEdit = service().archive("V04")

        assertFalse(again.archived)
        assertFalse(byEdit.archived)
        assertEquals(archivedAt, again.variation.variation.archivedAt)
        assertEquals("V04.1", byEdit.variation.variation.replacedByLabel)
        assertEquals(before, rows())
    }

    @Test
    fun `the baseline and an unknown label are refused, and nothing is stored`() {
        val before = rows()

        assertFailsWith<VariationBaselineException> { service().archive(VariationEditRules.BASELINE_LABEL) }
        assertFailsWith<VariationNotFoundException> { service().archive("V99") }

        assertEquals(before, rows())
    }

    @Test
    fun `a dotted label archives`() {
        service().edit("V04", request("req-1", config("80", "50")))

        val result = service().archive("V04.1")

        assertEquals(listOf(true, VariationState.ARCHIVED, "V04.1"), listOf(result.archived, result.variation.variation.state, result.variation.name.text))
        assertNull(result.variation.variation.replacedByLabel)
        assertEquals(plan.entries.map { it.label } - "V04", service().activePlan().entries.map { it.label })
    }

    @Test
    fun `an archived variation keeps its sweep runs and item events, unchanged in count and content`() {
        val seeded = seedHistory("V05")
        val runsBefore = sweepRuns.findAll().sortedBy { it.id }.map { it.id to it.label + it.outcome + it.runId + it.realizedPnl }
        val eventsBefore = itemEvents.findAll().sortedBy { it.id }.map { it.id to it.detail }

        service().archive("V05")

        assertEquals(seeded, sweepRuns.count())
        assertEquals(runsBefore, sweepRuns.findAll().sortedBy { it.id }.map { it.id to it.label + it.outcome + it.runId + it.realizedPnl })
        assertEquals(eventsBefore, itemEvents.findAll().sortedBy { it.id }.map { it.id to it.detail })
        assertEquals(1L, itemEvents.count())
        assertEquals(1L, items.count())
    }

    private fun seedHistory(label: String): Long {
        val at = local("2026-11-26", "12:00")
        sessions.save(AutomationRows.session("session-1", AutomationSessionState.CLOSED, at))
        items.save(AutomationRows.item(1, "session-1", AutomationItemState.SIMULATED, blockTime = at))
        sweepRuns.save(AutomationRows.sweepRun(AutomationIds.sweepRun("item-1", plan.id, label), "item-1", label))
        itemEvents.save(AutomationItemEventEntity(AutomationIds.itemEvent("item-1", 1), "item-1", 1, at, AutomationItemEventKind.SWEEP_ENTRY_RECORDED, null, null, null, null,
            null, null, null, null, null, null, null, null, """{"label":"$label"}"""))
        return sweepRuns.count()
    }

    private fun rows() = variationRows.findAll().sortedBy { it.seq }.map { Triple(it.label, it.state, it.version) }

    private fun request(requestId: String, config: String): AutomationVariationConfigRequest =
        json.readValue<AutomationVariationConfigRequest>("""{"requestId":"$requestId","config":$config}""")

    /** V04's configuration with the take profit and stop loss sent as percents, which is how the console sends a level. */
    private fun config(takeProfitPercent: String, stopLossPercent: String) =
        body(plan.entries.single { it.label == "V04" }.config, takeProfitPercent, stopLossPercent)

    private fun body(config: SimulationConfig, takeProfitPercent: String? = null, stopLossPercent: String? = null): String {
        val fields = linkedMapOf(
            "notional" to "\"${config.notional.toPlainString()}\"", "maxAgeSlots" to "${config.maxAgeSlots}", "dipBps" to "${config.dipBps}",
            "minMarketCapUsd" to "\"${config.minMarketCapUsd.toPlainString()}\"", "horizonSlots" to "${config.horizonSlots}",
            "latencySlots" to "${config.latencySlots}", "transactionCostSol" to "\"${config.transactionCostSol.toPlainString()}\"",
            "quoteMint" to "\"${config.quoteMint.value}\"", "dipWindowMs" to "${config.dipWindowMs}", "maxHoldMs" to "${config.maxHoldMs}",
            "hardExitStartMs" to "${config.hardExitStartMs}", "priceMeasure" to "\"${config.priceMeasure.name}\"",
            "followWalletSells" to "${config.followWalletSells}", "minSignalSol" to "\"${config.minSignalSol?.toPlainString()}\"",
        )
        if (takeProfitPercent != null) fields["takeProfitPercent"] = "\"$takeProfitPercent\"" else fields["takeProfitBps"] = "${config.takeProfitBps}"
        if (stopLossPercent != null) fields["stopLossPercent"] = "\"$stopLossPercent\"" else fields["stopLossBps"] = "${config.stopLossBps}"
        return fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    private fun service() = variations(properties(), clock)

    private fun variationsWith(transaction: AutomationVariationTransaction) = AutomationVariationService(
        variationRows, variationSeed, transaction, AutomationVariationViews(variationRows, variationMapper), variationMapper, ConsoleConfigValidator(), properties(), clock,
    )
}
