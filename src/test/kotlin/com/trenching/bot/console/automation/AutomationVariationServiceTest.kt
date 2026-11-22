package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationDaySeed.Companion.local
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.api.dto.AutomationVariationEditRequest
import com.trenching.bot.console.automation.application.AutomationVariationSeedTransaction
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.InvalidAutomationRequestException
import com.trenching.bot.console.automation.domain.InvalidVariationConfigException
import com.trenching.bot.console.automation.domain.SweepPlan
import com.trenching.bot.console.automation.domain.SweepPlans
import com.trenching.bot.console.automation.domain.Variation
import com.trenching.bot.console.automation.domain.VariationArchivedException
import com.trenching.bot.console.automation.domain.VariationBaselineException
import com.trenching.bot.console.automation.domain.VariationDuplicateException
import com.trenching.bot.console.automation.domain.VariationEditResult
import com.trenching.bot.console.automation.domain.VariationEditRules
import com.trenching.bot.console.automation.domain.VariationLimitReachedException
import com.trenching.bot.console.automation.domain.VariationNotFoundException
import com.trenching.bot.console.automation.domain.VariationUnchangedException
import com.trenching.bot.console.automation.domain.VariationState
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.console.domain.ConfigErrorCode
import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Limit
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

/** Editable-variations plan Rules 1, 4, 8 and 13: the stored variations, their seed from the code plan, and the reads that serve the sweep and the scoreboard. */
class AutomationVariationServiceTest : AutomationDatabaseTest() {
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val clock = MutableClock(local("2026-11-12", "13:00"))
    private val plan = SweepPlans.DECU_SWEEP_1
    private val edits by lazy { AutomationVariationRows(variationRows, variationMapper) }
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()

    @Test
    fun `seeding an empty plan stores V00 to V09 as active rows equal to the code plan, with no activation day`() {
        variationRows.deleteAllInBatch()

        service().seed()

        val stored = service().list()
        assertEquals(plan.entries.map { it.label }, stored.map { it.label })
        assertEquals(plan.entries.map { it.config }, stored.map { it.config }, "each configuration reads back exactly, numbers at scale 12")
        assertEquals((0..9).toList(), stored.map { it.seq })
        assertTrue(stored.all { it.state == VariationState.ACTIVE && it.planId == plan.id })
        assertTrue(stored.all { it.activeFrom == null && it.archivedAt == null && it.replacesLabel == null && it.replacedByLabel == null })
        assertEquals(clock.instant(), stored.first().createdAt)
        assertEquals(plan, service().activePlan())
    }

    @Test
    fun `a second seed changes nothing, however late it runs`() {
        variationRows.deleteAllInBatch()
        service().seed()
        val first = variationRows.findAll().sortedBy { it.seq }.map { Triple(it.id, it.createdAt, it.version) }
        clock.now = clock.now.plusSeconds(3600)

        service().seed()

        assertEquals(first, variationRows.findAll().sortedBy { it.seq }.map { Triple(it.id, it.createdAt, it.version) })
        assertEquals(10, variationRows.count())
    }

    @Test
    fun `a seed that meets the label key of a concurrent one counts as seeded`() {
        variationRows.deleteAllInBatch()
        val racing = object : AutomationVariationSeedTransaction(variationRows, variationMapper) {
            override fun seed(plan: SweepPlan, now: Instant): Boolean {
                variationRows.saveAllAndFlush(Variation.seed(plan, now).map { variationMapper.toEntity(it) })
                throw DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_automation_variations_label\"")
            }
        }

        variations(properties(), clock, racing).seed()

        assertEquals(10, variationRows.count())
    }

    @Test
    fun `a failed seed that left no rows is not hidden`() {
        variationRows.deleteAllInBatch()
        val broken = object : AutomationVariationSeedTransaction(variationRows, variationMapper) {
            override fun seed(plan: SweepPlan, now: Instant): Boolean =
                throw DataIntegrityViolationException("some other key")
        }

        assertFailsWith<DataIntegrityViolationException> { variations(properties(), clock, broken).seed() }
        assertEquals(0, variationRows.count())
    }

    @Test
    fun `a plan that has rows is left alone, so a later change to the code plan never rewrites history`() {
        edits.edit("V04", clock.instant(), "req-1")
        val before = variationRows.findAll().sortedBy { it.seq }.map { it.label to it.state }

        service().seed()

        assertEquals(before, variationRows.findAll().sortedBy { it.seq }.map { it.label to it.state })
        assertEquals(11, variationRows.count())
    }

    @Test
    fun `the list is ordered by sequence, a view reads one label, and an edit moves a label from active to archived`() {
        val created = edits.edit("V04", local("2026-11-12", "15:00"), "req-1")

        val all = service().list()
        val archived = service().listViews("ARCHIVED")

        assertEquals((0..10).map { "V" + it.toString().padStart(2, '0') }, all.map { it.label })
        assertEquals(all.map { it.label } - "V04", service().activePlan().entries.map { it.label })
        assertEquals(listOf("V04"), archived.items.map { it.variation.label })
        val old = service().findView("V04").variation
        assertEquals(VariationState.ARCHIVED, old.state)
        assertEquals(local("2026-11-12", "15:00"), old.archivedAt)
        assertEquals("V10", old.replacedByLabel)
        assertEquals(created, service().findView("V10").variation)
        assertEquals("V04", service().findView("V10").variation.replacesLabel)
        assertEquals(local("2026-11-12", "15:00"), service().findView("V10").variation.activeFrom)
        assertEquals(plan.entries.map { it.label } - "V04" + "V10", service().activePlan().entries.map { it.label })
        assertEquals(created.config, service().activePlan().entries.last().config)
        assertEquals(plan.entries.single { it.label == "V04" }.config, old.config, "the archived configuration is as it was")
    }

    @Test
    fun `an unknown state is refused on the state field, and an unknown label is not found`() {
        val refused = assertFailsWith<InvalidAutomationRequestException> { service().listViews("active") }

        assertEquals("state", refused.field)
        assertFailsWith<VariationNotFoundException> { service().findView("V99") }
    }

    @Test
    fun `no active variation gives an empty plan`() {
        edits.archiveAll(clock.instant())

        assertTrue(service().activePlan().entries.isEmpty())
        assertEquals(10, service().listViews("ARCHIVED").items.size)
    }

    @Test
    fun `startup seeds the plan and accepts the active variations`() {
        variationRows.deleteAllInBatch()

        service().start()
        service().start()

        assertEquals(10, variationRows.count())
    }

    @Test
    fun `startup fails naming an active variation whose minimum signal size is below the capture filter's`() {
        val low = SimulationConfig.of(
            BigDecimal("0.1"), 2, 2000, 5500, 4000, BigDecimal("10000"), 4500, 1, BigDecimal("0.001"), minSignalSol = BigDecimal("1.0"),
        )
        variationRows.saveAndFlush(variationMapper.toEntity(Variation(plan.id, "V10", 10, VariationState.ACTIVE, low, null, null, null, null, clock.instant())))

        val failure = assertFailsWith<IllegalArgumentException> { service().start() }

        assertTrue(failure.message.orEmpty().contains("labels=[V10]"), failure.message)
        assertTrue(failure.message.orEmpty().contains("capture-min-signal-sol=1.5"), failure.message)
    }

    @Test
    fun `an archived variation below the capture minimum does not fail startup`() {
        val low = SimulationConfig.of(
            BigDecimal("0.1"), 2, 2000, 5500, 4000, BigDecimal("10000"), 4500, 1, BigDecimal("0.001"), minSignalSol = BigDecimal("1.0"),
        )
        variationRows.saveAndFlush(variationMapper.toEntity(
            Variation(plan.id, "V10", 10, VariationState.ARCHIVED, low, null, clock.instant(), null, null, clock.instant()),
        ))

        service().start()

        assertNull(variationRows.findOneByPlanIdAndLabel(plan.id, "V10")?.replacedByLabel)
    }

    @Test
    fun `an edit of two settings archives the old row, creates V10 with both values and names what changed`() {
        sweepRuns.save(AutomationRows.sweepRun("r-v04", "item-1", "V04"))
        val runsBefore = sweepRuns.findAll().map { it.id to it.label }

        val result = service().edit("V04", edit("req-1", editBody()))

        val created = result.variation
        assertTrue(result.created)
        assertEquals(listOf("V10", 8000, 5000, "V04", VariationState.ACTIVE), listOf(created.variation.label, created.variation.config.takeProfitBps,
            created.variation.config.stopLossBps, created.variation.replacesLabel, created.variation.state))
        assertEquals(clock.instant(), created.variation.activeFrom)
        assertEquals(listOf("stopLossBps"), created.changedFromReplaced, "V04 already takes profit at 80%")
        assertEquals(listOf("takeProfitBps", "stopLossBps"), created.differsFromAgreed)
        val old = service().findView("V04").variation
        assertEquals(listOf(VariationState.ARCHIVED, clock.instant(), "V10"), listOf(old.state, old.archivedAt, old.replacedByLabel))
        assertEquals(created.variation, service().findView("V10").variation)
        assertEquals(11, variationRows.count())
        assertEquals(runsBefore, sweepRuns.findAll().map { it.id to it.label }, "no sweep run is touched by an edit")
        assertEquals("req-1", variationRows.findOneByPlanIdAndLabel(plan.id, "V10")?.requestId)
    }

    @Test
    fun `the same request id again returns the variation it created and writes nothing, even though the edited label is archived by now`() {
        val first = service().edit("V04", edit("req-1", editBody()))
        val rowsBefore = rows()
        clock.now = clock.now.plusSeconds(60)

        val again = service().edit("V04", edit("req-1", editBody(takeProfitPercent = "60")))
        val ofAnotherLabel = service().edit("V05", edit("req-1", editBody(takeProfitPercent = "61")))

        assertFalse(again.created)
        assertFalse(ofAnotherLabel.created)
        assertEquals(first.variation, again.variation)
        assertEquals(first.variation, ofAnotherLabel.variation)
        assertEquals(rowsBefore, rows())
    }

    @Test
    fun `a new request id with another active variation's configuration is a duplicate naming it, and stores nothing`() {
        service().edit("V04", edit("req-1", editBody()))
        val before = rows()

        val refused = assertFailsWith<VariationDuplicateException> { service().edit("V05", edit("req-2", editBody())) }

        assertEquals("V10", refused.label)
        assertEquals(before, rows())
    }

    @Test
    fun `an edit that is refused stores nothing, whichever the refusal`() {
        service().edit("V04", edit("req-1", editBody()))
        val before = rows()
        val own = body(plan.entries.single { it.label == "V05" }.config)

        assertFailsWith<VariationBaselineException> { service().edit("V00", edit("r-baseline", editBody())) }
        assertFailsWith<VariationArchivedException> { service().edit("V04", edit("r-archived", editBody(takeProfitPercent = "70"))) }
        assertFailsWith<VariationUnchangedException> { service().edit("V05", edit("r-unchanged", own)) }
        assertFailsWith<VariationNotFoundException> { service().edit("V99", edit("r-unknown", editBody())) }
        assertEquals(before, rows())
    }

    @Test
    fun `a request id that is blank or longer than 64 characters is refused on the requestId field before anything else, and 64 is accepted`() {
        listOf("", "  ", "x".repeat(65)).forEach { requestId ->
            val refused = assertFailsWith<InvalidAutomationRequestException> { service().edit("V99", edit(requestId, "{}")) }

            assertEquals("requestId", refused.field)
        }

        service().edit("V04", edit("x".repeat(64), editBody()))

        assertEquals("x".repeat(64), variationRows.findOneByPlanIdAndLabel(plan.id, "V10")?.requestId)
    }

    @Test
    fun `a configuration that does not parse is refused with its code and fields, after the label is known and nothing is stored`() {
        fun refusal(body: String): InvalidVariationConfigException = assertFailsWith { service().edit("V04", edit("req-1", body)) }

        val missingSignal = refusal(editBody(without = "minSignalSol"))
        val lowSignal = refusal(editBody(extra = mapOf("minSignalSol" to "\"1.49\"")))
        val missingField = refusal(editBody(without = "latencySlots"))
        val precision = refusal(editBody(takeProfitPercent = "80.555"))
        val disagree = refusal(editBody(extra = mapOf("takeProfitBps" to "7000")))
        val unknown = refusal(editBody(extra = mapOf("feeBps" to "5")))
        val notSol = refusal(editBody(extra = mapOf("quoteMint" to "\"11111111111111111111111111111112\"")))

        assertEquals(listOf(ConfigErrorCode.CONFIG_INVALID, setOf("minSignalSol")), listOf(missingSignal.code, missingSignal.fields.keys))
        assertEquals(setOf("minSignalSol"), lowSignal.fields.keys)
        assertEquals(setOf("latencySlots"), missingField.fields.keys)
        assertEquals(listOf(ConfigErrorCode.INVALID_PERCENT, setOf("takeProfitPercent")), listOf(precision.code, precision.fields.keys))
        assertEquals(listOf(ConfigErrorCode.CONFLICTING_FIELDS, setOf("takeProfitPercent")), listOf(disagree.code, disagree.fields.keys))
        assertEquals(setOf("feeBps"), unknown.fields.keys)
        assertEquals(setOf("quoteMint"), notSol.fields.keys)
        assertFailsWith<VariationNotFoundException> { service().edit("V99", edit("req-2", editBody(without = "minSignalSol"))) }
        assertEquals(10, variationRows.count())
    }

    @Test
    fun `two different edits of one label at once leave exactly one new variation, the other refused`() {
        val outcomes = concurrently(
            { service().edit("V04", edit("req-a", editBody(takeProfitPercent = "80"))) },
            { service().edit("V04", edit("req-b", editBody(takeProfitPercent = "81"))) },
        )

        assertEquals(1, outcomes.count { it.isSuccess })
        val failure = outcomes.single { it.isFailure }.exceptionOrNull()
        assertTrue(failure is VariationArchivedException || failure is OptimisticLockingFailureException, "$failure")
        assertEquals(11, variationRows.count())
        assertEquals(listOf("V10"), variationRows.findAll().filter { it.replacesLabel == "V04" }.map { it.label })
        assertEquals(VariationState.ARCHIVED, service().findView("V04").variation.state)
    }

    @Test
    fun `the same edit sent twice at once creates one variation and answers both with it`() {
        val results = concurrently(
            { service().edit("V04", edit("req-a", editBody())) },
            { service().edit("V04", edit("req-a", editBody())) },
        ).map { it.getOrThrow() }

        assertEquals(listOf(false, true), results.map { it.created }.sorted())
        assertEquals(1, results.map { it.variation.variation.label }.distinct().size)
        assertEquals(11, variationRows.count())
    }

    @Test
    fun `an edit that lands second reads what the first stored, so the same configuration for another label is a duplicate`() {
        val outcomes = concurrently(
            { service().edit("V04", edit("req-a", editBody())) },
            { service().edit("V05", edit("req-b", editBody())) },
        )

        assertEquals(1, outcomes.count { it.isSuccess })
        assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is VariationDuplicateException)
        assertEquals(11, variationRows.count())
    }

    @Test
    fun `a clock with sub-microsecond nanos gives the same variation on the 201, the repeat's 200, a read and the stored row`() {
        clock.now = clock.now.plusNanos(123_456_789)
        val microseconds = clock.now.truncatedTo(ChronoUnit.MICROS)

        val first = service().edit("V04", edit("req-1", editBody()))
        val again = service().edit("V04", edit("req-1", editBody()))

        assertTrue(first.created)
        assertFalse(again.created)
        assertEquals(first.variation, again.variation)
        assertEquals(first.variation, service().findView("V10"))
        assertEquals(microseconds, first.variation.variation.activeFrom)
        assertEquals(microseconds, first.variation.variation.createdAt)
        assertEquals(microseconds, variationRows.findOneByPlanIdAndLabel(plan.id, "V10")?.createdAt)
        assertEquals(microseconds, service().findView("V04").variation.archivedAt)
    }

    @Test
    fun `a seed stamps its rows at whole microseconds`() {
        variationRows.deleteAllInBatch()
        clock.now = clock.now.plusNanos(123_456_789)
        val stamped = mutableListOf<Instant>()
        val recording = object : AutomationVariationSeedTransaction(variationRows, variationMapper) {
            override fun seed(plan: SweepPlan, now: Instant): Boolean = super.seed(plan, now).also { stamped += now }
        }

        variations(properties(), clock, recording).seed()

        assertEquals(listOf(clock.now.truncatedTo(ChronoUnit.MICROS)), stamped)
    }

    @Test
    fun `an edit that would number a variation past the bound of a plan is refused and stores nothing`() {
        val last = AutomationLimits.MAX_VARIATIONS_PER_PLAN - 2
        variationRows.saveAndFlush(AutomationVariationRows.entityLike(
            checkNotNull(variationRows.findOneByPlanIdAndLabel(plan.id, "V05")), label = VariationEditRules.labelOf(last), seq = last,
        ))

        val fits = service().edit("V04", edit("req-1", editBody()))
        val before = rows()
        val refused = assertFailsWith<VariationLimitReachedException> { service().edit("V03", edit("req-2", editBody(takeProfitPercent = "61"))) }

        assertEquals(VariationEditRules.labelOf(AutomationLimits.MAX_VARIATIONS_PER_PLAN - 1), fits.variation.variation.label)
        assertEquals(plan.id, refused.planId)
        assertEquals(before, rows())
    }

    @Test
    fun `two edits of different labels at once both succeed as V10 and V11, so the plan's lock serialises the sequence`() {
        val outcomes = concurrentlyBehindLock(
            { service().edit("V04", edit("req-a", editBody())) },
            { service().edit("V05", edit("req-b", editBody(takeProfitPercent = "61"))) },
        ).map { it.getOrThrow() }

        assertTrue(outcomes.all { it.created })
        assertEquals(listOf("V10", "V11"), outcomes.map { it.variation.variation.label }.sorted())
        assertEquals(12, variationRows.count())
        assertEquals(listOf("V04", "V05"), variationRows.findAll().mapNotNull { it.replacesLabel }.sorted())
        assertEquals(setOf(10, 11), variationRows.findAll().filter { it.replacesLabel != null }.map { it.seq }.toSet())
    }

    @Test
    fun `the views carry the difference lists, filtered by state, and one view reads a label`() {
        service().edit("V04", edit("req-1", editBody()))

        val all = service().listViews(null)
        val archived = service().listViews("ARCHIVED")

        assertEquals(plan.id, all.planId)
        assertEquals((0..10).map { VariationEditRules.labelOf(it) }, all.items.map { it.variation.label })
        assertEquals(emptyList(), all.items.first().differsFromAgreed, "V00 is the agreed configuration")
        assertEquals(emptyList(), all.items.first().changedFromReplaced)
        assertEquals(listOf("V04"), archived.items.map { it.variation.label })
        assertEquals(listOf("stopLossBps"), service().findView("V10").changedFromReplaced)
        assertEquals(listOf("V10"), service().listViews("ACTIVE").items.filter { it.variation.replacesLabel != null }.map { it.variation.label })
        assertFailsWith<InvalidAutomationRequestException> { service().listViews("active") }
        assertFailsWith<VariationNotFoundException> { service().findView("V99") }
    }

    private fun rows() = variationRows.findAll().sortedBy { it.seq }.map { Triple(it.label, it.state, it.version) }

    private fun edit(requestId: String, config: String): AutomationVariationEditRequest =
        json.readValue<AutomationVariationEditRequest>("""{"requestId":"$requestId","config":$config}""")

    /** V04's configuration with the take profit and stop loss sent as percents, which is how the console sends a level. */
    private fun editBody(takeProfitPercent: String = "80", stopLossPercent: String = "50", without: String? = null, extra: Map<String, String> = emptyMap()) =
        body(plan.entries.single { it.label == "V04" }.config, takeProfitPercent, stopLossPercent, without, extra)

    private fun body(
        config: SimulationConfig,
        takeProfitPercent: String? = null,
        stopLossPercent: String? = null,
        without: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): String {
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
        fields.putAll(extra)
        without?.let(fields::remove)
        return fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    private fun concurrently(vararg tasks: () -> VariationEditResult): List<Result<VariationEditResult>> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(tasks.size)
        try {
            val futures = tasks.map { task -> pool.submit(Callable { start.await(); runCatching(task) }) }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Runs [tasks] on threads of their own while the test holds the plan's active rows locked, and lets go once they are all under
     * way and a session waits for the lock: the edits really overlap, so only the lock puts them one after the other.
     */
    private fun concurrentlyBehindLock(vararg tasks: () -> VariationEditResult): List<Result<VariationEditResult>> {
        val started = CountDownLatch(tasks.size)
        val pool = Executors.newFixedThreadPool(tasks.size)
        try {
            val futures = checkNotNull(TransactionTemplate(transactionManager).execute {
                variationRows.findAllForUpdateByPlanIdAndStateOrderBySeqAsc(plan.id, VariationState.ACTIVE, Limit.of(AutomationLimits.MAX_VARIATIONS_PER_PLAN))
                val running = tasks.map { task -> pool.submit(Callable { started.countDown(); runCatching(task) }) }
                assertTrue(started.await(30, TimeUnit.SECONDS), "every edit started")
                awaitLockWaiterOr { false }
                running
            })
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun service() = variations(properties(), clock)
}
