package com.trenching.bot.console

import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.api.dto.ConsoleConfigRequest
import com.trenching.bot.console.application.ConsoleConfigValidator
import com.trenching.bot.console.domain.ConfigValidation
import com.trenching.bot.console.domain.NumberRange
import com.trenching.bot.console.domain.SimulationConfigLimits
import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

class ConsoleConfigRequestTest {
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()
    private val validator = ConsoleConfigValidator()

    private fun validate(body: String?): ConfigValidation =
        validator.validate(body?.let { json.readValue<ConsoleConfigRequest>(it) })

    private fun fields(body: String): Map<String, String> = assertIs<ConfigValidation.Invalid>(validate(body)).fields

    @Test
    fun `a missing config is the mvp configuration`() {
        assertEquals(SimulationConfig.mvp(), assertIs<ConfigValidation.Valid>(validate(null)).config)
    }

    @Test
    fun `an empty config falls back to the mvp value of every field`() {
        assertEquals(SimulationConfig.mvp(), assertIs<ConfigValidation.Valid>(validate("{}")).config)
    }

    @Test
    fun `the default request spelled out equals the mvp configuration`() {
        val body = """{"notional":"100","feeBps":10,"slippageBps":50,"maxWaitSlots":2,"maxAgeSlots":2,
            |"holdingSlots":5,"quoteMint":"So11111111111111111111111111111111111111112"}""".trimMargin()

        assertEquals(SimulationConfig.mvp(), assertIs<ConfigValidation.Valid>(validate(body)).config)
    }

    @Test
    fun `edited values build the configuration through SimulationConfig of`() {
        val body = """{"notional":"25.5","feeBps":0,"slippageBps":9999,"maxWaitSlots":1000,"maxAgeSlots":0,
            |"holdingSlots":1,"quoteMint":"11111111111111111111111111111112"}""".trimMargin()

        val expected = SimulationConfig.of(BigDecimal("25.5"), 0, 9999, 1000, 0, 1,
            quoteMint = MintAddress.of("11111111111111111111111111111112"))
        assertEquals(expected, assertIs<ConfigValidation.Valid>(validate(body)).config)
    }

    @Test
    fun `every out-of-range field yields its own field error`() {
        val body = """{"notional":"0","feeBps":10000,"slippageBps":-1,"maxWaitSlots":1001,"maxAgeSlots":-1,
            |"holdingSlots":0,"quoteMint":"not-a-mint"}""".trimMargin()

        assertEquals(setOf("notional", "feeBps", "slippageBps", "maxWaitSlots", "maxAgeSlots", "holdingSlots", "quoteMint"),
            fields(body).keys)
    }

    @Test
    fun `fee bps of 10000 is rejected on the fee field only`() {
        assertEquals(setOf("feeBps"), fields("""{"feeBps":10000}""").keys)
    }

    @Test
    fun `notional as a JSON number is rejected`() {
        assertEquals(setOf("notional"), fields("""{"notional":100}""").keys)
    }

    @ParameterizedTest
    @ValueSource(strings = ["1e2", "1E2", "", " 100", "100.", ".5", "+100", "abc", "-5"])
    fun `notional that is not a positive plain decimal string is rejected`(notional: String) {
        assertEquals(setOf("notional"), fields("""{"notional":"$notional"}""").keys)
    }

    @Test
    fun `notional that rounds to zero at scale 12 is rejected`() {
        assertEquals(setOf("notional"), fields("""{"notional":"0.0000000000004"}""").keys)
    }

    @Test
    fun `notional beyond the stored precision is rejected`() {
        assertEquals(setOf("notional"), fields("""{"notional":"${"9".repeat(27)}"}""").keys)
    }

    @Test
    fun `notional is normalized to scale 12 half even`() {
        val config = assertIs<ConfigValidation.Valid>(validate("""{"notional":"1.0000000000005"}""")).config

        assertEquals("1.000000000000", config.notional.toPlainString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["10.5", "\"10\"", "true", "99999999999"])
    fun `a non-integer basis point value is rejected`(value: String) {
        assertEquals(setOf("feeBps"), fields("""{"feeBps":$value}""").keys)
    }

    @Test
    fun `the shared limits are exactly the bounds SimulationConfig of enforces`() {
        val limits = SimulationConfigLimits.CURRENT
        fun config(fee: Long = 10, slippage: Long = 50, wait: Long = 2, age: Long = 2, holding: Long = 5) =
            SimulationConfig.of(BigDecimal("100"), fee.toInt(), slippage.toInt(), wait, age, holding)
        val bounded = listOf<Pair<NumberRange, (Long) -> SimulationConfig>>(
            limits.feeBps to { config(fee = it) },
            limits.slippageBps to { config(slippage = it) },
            limits.maxWaitSlots to { config(wait = it) },
            limits.maxAgeSlots to { config(age = it) },
        )

        bounded.forEach { (range, build) ->
            val max = requireNotNull(range.max)
            build(range.min)
            build(max)
            assertFailsWith<IllegalArgumentException> { build(range.min - 1) }
            assertFailsWith<IllegalArgumentException> { build(max + 1) }
        }
        config(holding = limits.holdingSlots.min)
        assertFailsWith<IllegalArgumentException> { config(holding = limits.holdingSlots.min - 1) }
        assertNull(limits.holdingSlots.max)
    }

    @Test
    fun `the range boundaries agree with SimulationConfig of`() {
        val body = """{"feeBps":9999,"slippageBps":0,"maxWaitSlots":0,"maxAgeSlots":1000,"holdingSlots":1}"""

        assertIs<ConfigValidation.Valid>(validate(body))
    }
}
