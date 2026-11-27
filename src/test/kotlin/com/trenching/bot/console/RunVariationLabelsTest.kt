package com.trenching.bot.console

import com.trenching.bot.console.application.RunVariationLabels
import com.trenching.bot.console.domain.RunVariationLookup
import com.trenching.bot.support.FakeRunVariationLookup
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RunVariationLabelsTest {
    private val calls = mutableListOf<List<String>>()
    private val labels = RunVariationLabels(FakeRunVariationLookup(mapOf("V03" to setOf("h1", "h2"), "V05" to emptySet())) { runIds ->
        calls += runIds.toList()
        mapOf("run-1" to listOf("V03", "V12"))
    })

    @Test
    fun `no run ids make no lookup call`() {
        assertEquals(emptyMap(), labels.of(emptyList()))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `every requested run has an entry, empty when the lookup knows none of its labels`() {
        val found = labels.of(listOf("run-1", "run-2"))

        assertEquals(mapOf("run-1" to listOf("V03", "V12"), "run-2" to emptyList()), found)
        assertEquals(listOf("run-1", "run-2"), found.keys.toList())
    }

    @Test
    fun `duplicate run ids are asked once`() {
        labels.of(listOf("run-1", "run-2", "run-1"))

        assertEquals(listOf(listOf("run-1", "run-2")), calls)
    }

    @Test
    fun `one run is looked up with one call`() {
        assertEquals(listOf("V03", "V12"), labels.of("run-1"))
        assertEquals(emptyList(), labels.of("run-9"))
        assertEquals(2, calls.size)
    }

    @Test
    fun `without a lookup every run has no variation`() {
        val absent = RunVariationLabels(RunVariationLookup.ABSENT)

        assertEquals(mapOf("run-1" to emptyList<String>()), absent.of(listOf("run-1")))
        assertNull(absent.configurationHashes("V03"))
    }

    @Test
    fun `the hashes of a label come from the lookup, null for an unknown label and empty for one that has not run`() {
        assertEquals(setOf("h1", "h2"), labels.configurationHashes("V03"))
        assertEquals(emptySet(), labels.configurationHashes("V05"))
        assertNull(labels.configurationHashes("V99"))
    }
}
