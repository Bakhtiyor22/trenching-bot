package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationRows.item
import com.trenching.bot.console.automation.AutomationRows.sweepRun
import com.trenching.bot.console.automation.application.AutomationRunVariationLookup
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class AutomationRunVariationLookupTest : AutomationDatabaseTest() {
    private val lookup get() = AutomationRunVariationLookup(sweepRuns, AutomationFixtures.properties())

    @Test
    fun `a run lists the configured plan's labels ordered by their number, not as text`() {
        items.save(item(1, "s1", blockTime = EVENING))
        sweepRuns.saveAll(listOf(
            sweepRun("r1", "item-1", "V100", runId = "run-a"),
            sweepRun("r2", "item-1", "V10", runId = "run-a"),
            sweepRun("r3", "item-1", "V02", runId = "run-a"),
            sweepRun("r4", "item-1", "V99", runId = "run-a"),
        ))

        assertEquals(mapOf("run-a" to listOf("V02", "V10", "V99", "V100")), lookup.labels(listOf("run-a")))
    }

    @Test
    fun `a label that is not V and digits sorts after the numbered ones`() {
        items.save(item(1, "s1", blockTime = EVENING))
        sweepRuns.saveAll(listOf(
            sweepRun("r1", "item-1", "X1", runId = "run-a"),
            sweepRun("r2", "item-1", "V100", runId = "run-a"),
            sweepRun("r3", "item-1", "V02", runId = "run-a"),
        ))

        assertEquals(mapOf("run-a" to listOf("V02", "V100", "X1")), lookup.labels(listOf("run-a")))
    }

    @Test
    fun `each run gets its own labels and a run without a row is left out of the map`() {
        items.saveAll(listOf(item(1, "s1", blockTime = EVENING), item(2, "s1", blockTime = EVENING)))
        sweepRuns.saveAll(listOf(
            sweepRun("r1", "item-1", "V03", runId = "run-a"),
            sweepRun("r2", "item-2", "V12", runId = "run-a"),
            sweepRun("r3", "item-2", "V04", runId = "run-b"),
        ))

        assertEquals(
            mapOf("run-a" to listOf("V03", "V12"), "run-b" to listOf("V04")),
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
