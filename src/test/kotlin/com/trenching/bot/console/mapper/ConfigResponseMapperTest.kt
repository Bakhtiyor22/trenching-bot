package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.DecimalRangeResponse
import com.trenching.bot.console.domain.ConfigDefaults
import com.trenching.bot.console.domain.SimulationConfigLimits
import com.trenching.bot.strategy.domain.SimulationConfig
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ConfigResponseMapperTest {
    private val mapper = ConfigResponseMapperImpl()

    @Test
    fun `the defaults apply no minimum signal size and the limits give its whole-lamport range`() {
        val response = mapper.toResponse(ConfigDefaults(SimulationConfig.mvp(), SimulationConfigLimits.CURRENT))

        assertNull(response.defaults.minSignalSol)
        assertEquals(9, response.limits.minSignalSolScale)
        assertEquals(DecimalRangeResponse("0.000000001", "1000"), response.limits.minSignalSol)
    }

    @Test
    fun `a run's minimum signal size crosses as an exact decimal string`() {
        assertEquals("1.900000000000", mapper.toResponse(SimulationConfig.agreed()).minSignalSol)
    }
}
