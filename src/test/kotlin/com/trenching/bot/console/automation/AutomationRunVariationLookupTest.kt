package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationRows.item
import com.trenching.bot.console.automation.AutomationRows.sweepRun
import com.trenching.bot.console.automation.application.AutomationRunVariationLookup
import com.trenching.bot.console.automation.application.AutomationVariationViews
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.Variation
import com.trenching.bot.console.automation.domain.VariationState
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AutomationRunVariationLookupTest : AutomationDatabaseTest() {
    private val lookup get() = AutomationRunVariationLookup(sweepRuns, variationRows, AutomationVariationViews(variationRows, variationMapper), AutomationFixtures.properties())

    @Test
    fun `a run lists the configured plan's labels ordered by their names, not as text and not by creation`() {
        items.save(item(1, "s1", blockTime = EVENING))
        val edit = AutomationVariationRows(variationRows, variationMapper).edit("V03", EVENING, "request-1").label
        sweepRuns.saveAll(listOf(
            sweepRun("r1", "item-1", "V09", runId = "run-a"),
            sweepRun("r2", "item-1", edit, runId = "run-a"),
            sweepRun("r3", "item-1", "V04", runId = "run-a"),
            sweepRun("r4", "item-1", "V03", runId = "run-a"),
            sweepRun("r5", "item-1", "V02", runId = "run-a"),
        ))

        assertEquals("V10", edit, "the edit of V03 is stored as V10, the legacy label, and named V03.1")
        assertEquals(mapOf("run-a" to listOf("V02", "V03", edit, "V04", "V09")), lookup.labels(listOf("run-a")))
    }

    @Test
    fun `the hashes of a label are its distinct non-null hashes, two rows with one hash giving one`() {
        items.saveAll(listOf(item(1, "s1", blockTime = EVENING), item(2, "s1", blockTime = EVENING), item(3, "s1", blockTime = EVENING), item(4, "s1", blockTime = EVENING)))
        sweepRuns.saveAll(listOf(
            sweepRun("r1", "item-1", "V03", runId = "run-a").apply { configurationHash = "hash-1" },
            sweepRun("r2", "item-2", "V03", runId = "run-b").apply { configurationHash = "hash-1" },
            sweepRun("r3", "item-3", "V03", runId = "run-c").apply { configurationHash = "hash-2" },
            sweepRun("r4", "item-4", "V03"),
            sweepRun("r5", "item-1", "V04", runId = "run-d").apply { configurationHash = "hash-9" },
            sweepRun("r6", "item-2", "V04", runId = "run-e").apply { planId = "other-plan"; configurationHash = "hash-8" },
        ))

        assertEquals(setOf("hash-1", "hash-2"), lookup.configurationHashes("V03"))
    }

    @Test
    fun `more distinct hashes than the bound fail naming the plan, the label and the bound, and exactly the bound is read`() {
        val bound = AutomationLimits.MAX_HASHES_PER_VARIATION
        items.saveAll((1..bound + 1).map { item(it, "s1", blockTime = EVENING) })
        sweepRuns.saveAll((1..bound).map { n -> sweepRun("r$n", "item-$n", "V03", runId = "run-$n").apply { configurationHash = "hash-$n" } })

        assertEquals(bound, lookup.configurationHashes("V03")?.size)

        sweepRuns.save(sweepRun("r-over", "item-${bound + 1}", "V03", runId = "run-over").apply { configurationHash = "hash-over" })

        val failure = assertFailsWith<IllegalStateException> { lookup.configurationHashes("V03") }
        val message = failure.message.orEmpty()
        assertTrue("decu-sweep-1" in message && "V03" in message && "$bound" in message, message)
    }

    @Test
    fun `names are read only where they are needed, so a broken line fails the labels of a run but not an empty page or a hash read`() {
        items.save(item(1, "s1", blockTime = EVENING))
        val config = variationMapper.toConfig(checkNotNull(variationRows.findOneByPlanIdAndLabel("decu-sweep-1", "V05")))
        variationRows.saveAndFlush(variationMapper.toEntity(Variation("decu-sweep-1", "V10", 10, VariationState.ACTIVE, config, EVENING, null, "V77", null, EVENING)))
        sweepRuns.save(sweepRun("r1", "item-1", "V03", runId = "run-a").apply { configurationHash = "hash-1" })

        assertEquals(emptyMap(), lookup.labels(listOf("run-none")))
        assertEquals(setOf("hash-1"), lookup.configurationHashes("V03"))
        assertNull(lookup.configurationHashes("V99"))
        assertFailsWith<IllegalStateException> { lookup.labels(listOf("run-a")) }
    }

    @Test
    fun `a variation that exists but has not run has an empty set, and an unknown label has none`() {
        assertEquals(emptySet(), lookup.configurationHashes("V05"))
        assertNull(lookup.configurationHashes("V99"))
    }

    @Test
    fun `an archived variation filters like an active one`() {
        items.save(item(1, "s1", blockTime = EVENING))
        val edit = AutomationVariationRows(variationRows, variationMapper).edit("V03", EVENING, "request-1").label
        sweepRuns.save(sweepRun("r1", "item-1", "V03", runId = "run-a").apply { configurationHash = "hash-1" })

        assertEquals(setOf("hash-1"), lookup.configurationHashes("V03"), "V03 is archived by its edit")
        assertEquals(emptySet(), lookup.configurationHashes(edit))
    }

    @Test
    fun `a label that no variation of the plan names sorts after the named ones`() {
        items.save(item(1, "s1", blockTime = EVENING))
        sweepRuns.saveAll(listOf(
            sweepRun("r1", "item-1", "X1", runId = "run-a"),
            sweepRun("r2", "item-1", "V05", runId = "run-a"),
            sweepRun("r3", "item-1", "V02", runId = "run-a"),
        ))

        assertEquals(mapOf("run-a" to listOf("V02", "V05", "X1")), lookup.labels(listOf("run-a")))
    }

    @Test
    fun `each run gets its own labels and a run without a row is left out of the map`() {
        items.saveAll(listOf(item(1, "s1", blockTime = EVENING), item(2, "s1", blockTime = EVENING)))
        sweepRuns.saveAll(listOf(
            sweepRun("r1", "item-1", "V03", runId = "run-a"),
            sweepRun("r2", "item-2", "V05", runId = "run-a"),
            sweepRun("r3", "item-2", "V04", runId = "run-b"),
        ))

        assertEquals(
            mapOf("run-a" to listOf("V03", "V05"), "run-b" to listOf("V04")),
            lookup.labels(listOf("run-a", "run-b", "run-none")),
        )
    }

    @Test
    fun `a refused entry has no run and never contributes`() {
        items.save(item(1, "s1", blockTime = EVENING))
        sweepRuns.saveAll(listOf(
            sweepRun("r1", "item-1", "V03", runId = "run-a"),
            sweepRun("r2", "item-1", "V04"),
        ))

        assertEquals(mapOf("run-a" to listOf("V03")), lookup.labels(listOf("run-a")))
    }

    @Test
    fun `another plan's rows never contribute`() {
        items.save(item(1, "s1", blockTime = EVENING))
        sweepRuns.saveAll(listOf(
            sweepRun("r1", "item-1", "V03", runId = "run-a"),
            sweepRun("r2", "item-1", "V04", runId = "run-a").apply { planId = "other-plan" },
        ))

        assertEquals(mapOf("run-a" to listOf("V03")), lookup.labels(listOf("run-a")))
    }

    @Test
    fun `the same label on one run in two sessions is listed once`() {
        items.saveAll(listOf(item(1, "s1", blockTime = EVENING), item(2, "s2", blockTime = EVENING)))
        sweepRuns.saveAll(listOf(
            sweepRun("r1", "item-1", "V03", sessionId = "s1", runId = "run-a"),
            sweepRun("r2", "item-2", "V03", sessionId = "s2", runId = "run-a"),
        ))

        assertEquals(mapOf("run-a" to listOf("V03")), lookup.labels(listOf("run-a")))
    }
}
