package com.trenching.bot.console.automation.domain

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class EvaluationRankingTest {
    @Test
    fun `the best evaluation has the best status, then the highest worst-case P&L, then the first label`() {
        val scores = listOf(
            EvaluationScore("V03", EvaluationStatus.NO_RESULT, BigDecimal("5")),
            EvaluationScore("V02", EvaluationStatus.NO_SUCCESS, BigDecimal("-0.1")),
            EvaluationScore("V01", EvaluationStatus.NO_SUCCESS, BigDecimal("-0.100000000000")),
            EvaluationScore("V04", EvaluationStatus.NO_SUCCESS, BigDecimal("-0.2")),
        )

        assertEquals(EvaluationScore("V01", EvaluationStatus.NO_SUCCESS, BigDecimal("-0.100000000000")), EvaluationRanking.best(scores))
    }

    @Test
    fun `success outranks a higher P&L without success`() {
        val success = EvaluationScore("V09", EvaluationStatus.SUCCESS, BigDecimal("0.001"))

        assertEquals(success, EvaluationRanking.best(listOf(EvaluationScore("V00", EvaluationStatus.NO_SUCCESS, BigDecimal("9")), success)))
    }

    @Test
    fun `no evaluations have no best`() {
        assertNull(EvaluationRanking.best(emptyList()))
    }
}
