package com.trenching.bot.replay

import com.trenching.bot.replay.domain.RunStatus
import java.math.BigDecimal
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class RunStatusTest {
    @Test
    fun `a run with no filled order is no entry whatever its other totals say`() {
        assertEquals(RunStatus.NO_ENTRY, RunStatus.of(0, 0, BigDecimal.ZERO))
        assertEquals(RunStatus.NO_ENTRY, RunStatus.of(0, 2, BigDecimal("5")))
        assertEquals(RunStatus.NO_ENTRY, RunStatus.of(0, 0, BigDecimal("-5")))
    }

    @Test
    fun `an open position makes a filled run open whatever its realized pnl says`() {
        assertEquals(RunStatus.OPEN, RunStatus.of(3, 1, BigDecimal("-5")))
        assertEquals(RunStatus.OPEN, RunStatus.of(1, 1, BigDecimal("5")))
    }

    @Test
    fun `a fully closed run is judged by the exact sign of its realized pnl`() {
        assertEquals(RunStatus.PROFIT, RunStatus.of(2, 0, BigDecimal("0.000000000001")))
        assertEquals(RunStatus.LOSS, RunStatus.of(2, 0, BigDecimal("-0.000000000001")))
        assertEquals(RunStatus.FLAT, RunStatus.of(2, 0, BigDecimal("0.000000000000")))
        assertEquals(RunStatus.FLAT, RunStatus.of(2, 0, BigDecimal.ZERO))
    }
}
