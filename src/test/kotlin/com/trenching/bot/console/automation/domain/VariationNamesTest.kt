package com.trenching.bot.console.automation.domain

import com.trenching.bot.strategy.domain.SimulationConfig
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A variation's name is derived from its edit line and never stored (variation-versions plan Key Decision 1 and Domain Rules 1 to 5). */
class VariationNamesTest {
    @Test
    fun `a variation that replaces nothing is named by its own label`() {
        val names = VariationNames.of(seeded(10))

        assertEquals((0..9).map { "V0$it" }, (0..9).map { names.getValue("V0$it").text })
    }

    @Test
    fun `the six edits made on the server take the names of their lines`() {
        val names = VariationNames.of(server())

        assertEquals(
            mapOf("V10" to "V03.1", "V12" to "V03.2", "V11" to "V04.1", "V13" to "V09.1", "V14" to "V01.1", "V15" to "V07.1"),
            listOf("V10", "V12", "V11", "V13", "V14", "V15").associateWith { names.getValue(it).text },
        )
    }

    @Test
    fun `a name does not depend on the order the rows are given in`() {
        assertEquals(VariationNames.of(server()), VariationNames.of(server().reversed()))
    }

    @Test
    fun `an edit of V12 is stored as V03 point 3`() {
        val names = VariationNames.of(server())

        assertEquals("V03.3", VariationNames.editLabel(names.getValue("V12")))
        assertEquals("V03.1", VariationNames.editLabel(names.getValue("V03")))
        assertEquals("V04.2", VariationNames.editLabel(names.getValue("V11")))
    }

    @Test
    fun `the next base label is one past the highest number any label starts with, padded to two digits`() {
        assertEquals("V16", VariationNames.nextBaseLabel(server().map { it.label }))
        assertEquals("V10", VariationNames.nextBaseLabel(seeded(10).map { it.label }))
        assertEquals("V100", VariationNames.nextBaseLabel(listOf("V99")))
        assertEquals("V08", VariationNames.nextBaseLabel(listOf("V03.7", "V07.12", "V02")))
    }

    @Test
    fun `labels that are not V digits and an optional dotted tail are ignored for the next base`() {
        assertEquals("V04", VariationNames.nextBaseLabel(listOf("V03", "X99", "V", "Vabc", "V99x", "v50", "")))
        assertEquals("V00", VariationNames.nextBaseLabel(emptyList()))
    }

    @Test
    fun `a replaced label that names no row of the plan fails the read naming the label`() {
        val broken = seeded(2) + variation("V10", 10, replaces = "V77")

        val failure = assertFailsWith<IllegalStateException> { VariationNames.of(broken) }

        assertTrue(failure.message.orEmpty().contains("V10") && failure.message.orEmpty().contains("V77"), failure.message)
    }

    @Test
    fun `a replaces chain that loops fails the read naming a label of the loop`() {
        val loop = seeded(1) + variation("V10", 10, replaces = "V11") + variation("V11", 11, replaces = "V10")

        val failure = assertFailsWith<IllegalStateException> { VariationNames.of(loop) }

        assertTrue(failure.message.orEmpty().contains("V10") || failure.message.orEmpty().contains("V11"), failure.message)
    }

    @Test
    fun `names order by the number of the line, then by revision`() {
        val names = listOf("V16", "V03.2", "V01", "V03", "V00", "V03.1", "V02", "V01.1").map { name(it) }

        assertEquals(
            listOf("V00", "V01", "V01.1", "V02", "V03", "V03.1", "V03.2", "V16"),
            names.sortedWith(VariationName.ORDER).map { it.text },
        )
    }

    @Test
    fun `lines compare by number and not as text, and a line without a number sorts last`() {
        val sorted = listOf("V100", "X1", "V10", "V02", "V9.1").map { name(it) }.sortedWith(VariationName.ORDER)

        assertEquals(listOf("V02", "V9.1", "V10", "V100", "X1"), sorted.map { it.text })
    }

    private fun name(text: String) = VariationName(text.substringBefore('.'), text.substringAfter('.', "0").toInt())

    private fun seeded(count: Int) = (0 until count).map { variation(VariationEditRules.labelOf(it), it) }

    /** V00 to V09, then the edits as they are stored on the server: V10 of V03, V11 of V04, V12 of V10, V13 of V09, V14 of V01, V15 of V07. */
    private fun server() = seeded(10) + listOf(
        variation("V10", 10, replaces = "V03"), variation("V11", 11, replaces = "V04"), variation("V12", 12, replaces = "V10"),
        variation("V13", 13, replaces = "V09"), variation("V14", 14, replaces = "V01"), variation("V15", 15, replaces = "V07"),
    )

    private fun variation(label: String, seq: Int, replaces: String? = null) =
        Variation("decu-sweep-1", label, seq, VariationState.ACTIVE, SimulationConfig.agreed(), null, null, replaces, null, Instant.EPOCH)
}
