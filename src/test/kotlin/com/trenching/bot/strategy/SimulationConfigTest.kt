package com.trenching.bot.strategy

import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class SimulationConfigTest {
    @Test
    fun `notional cannot round down to zero at the model scale`() {
        assertFailsWith<IllegalArgumentException> { SimulationConfig.of(BigDecimal("0.0000000000001"), 10, 50, 2, 2, 5) }
    }

    @Test
    fun `cost and slot configuration must be finite and valid`() {
        assertFailsWith<IllegalArgumentException> { SimulationConfig.of(BigDecimal.ONE, 10000, 50, 2, 2, 5) }
        assertFailsWith<IllegalArgumentException> { SimulationConfig.of(BigDecimal.ONE, 10, -1, 2, 2, 5) }
        assertFailsWith<IllegalArgumentException> { SimulationConfig.of(BigDecimal.ONE, 10, 50, -1, 2, 5) }
        assertFailsWith<IllegalArgumentException> { SimulationConfig.of(BigDecimal.ONE, 10, 50, 2, 2, 0) }
    }
}
