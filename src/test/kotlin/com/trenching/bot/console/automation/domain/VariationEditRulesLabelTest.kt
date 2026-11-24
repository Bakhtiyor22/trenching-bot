package com.trenching.bot.console.automation.domain

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class VariationEditRulesLabelTest {
    @ParameterizedTest
    @ValueSource(ints = [0, 9, 10, 99, 100])
    fun `a label names the sequence number it was made from`(seq: Int) {
        assertEquals(seq, VariationEditRules.seqOf(VariationEditRules.labelOf(seq)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["V", "X03", "V1a", "", "v03", "V-1"])
    fun `a text that is not V and digits names no number`(label: String) {
        assertNull(VariationEditRules.seqOf(label))
    }

    @org.junit.jupiter.api.Test
    fun `labels are two digits wide until the number needs more`() {
        assertEquals(listOf("V00", "V09", "V10", "V99", "V100"), listOf(0, 9, 10, 99, 100).map(VariationEditRules::labelOf))
    }
}
