package com.trenching.bot.config.schema

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class GeneratedCheckNameTest {
    @Test
    fun `a check over one column is named table, column and check, with a number after a clash`() {
        assertTrue(GeneratedCheckName.matches("positions_close_reason_check", "positions", "close_reason"))
        assertTrue(GeneratedCheckName.matches("positions_close_reason_check2", "positions", "close_reason"))
    }

    @Test
    fun `any other name is not one PostgreSQL generated for the column`() {
        listOf("ck_positions_close_reason", "positions_close_reason_check_old", "positions_exit_trigger_reason_check", "close_reason_check")
            .forEach { assertFalse(GeneratedCheckName.matches(it, "positions", "close_reason"), it) }
    }

    @Test
    fun `a name over 63 characters is cut from the longer part first`() {
        val table = "t".repeat(40)
        val column = "c".repeat(30)

        assertTrue(GeneratedCheckName.matches("${"t".repeat(28)}_${"c".repeat(28)}_check", table, column))
        assertTrue(GeneratedCheckName.matches("${"t".repeat(28)}_${"c".repeat(27)}_check1", table, column))
        assertFalse(GeneratedCheckName.matches("${table}_${column}_check", table, column))
    }
}
