package com.trenching.bot.console.automation.domain

import kotlin.test.assertEquals

class VariationEditRulesLabelTest {
    @org.junit.jupiter.api.Test
    fun `labels are two digits wide until the number needs more`() {
        assertEquals(listOf("V00", "V09", "V10", "V99", "V100"), listOf(0, 9, 10, 99, 100).map(VariationEditRules::labelOf))
    }
}
