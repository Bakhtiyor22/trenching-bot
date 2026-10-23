package com.trenching.bot.console

import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.api.dto.ConsoleConfigRequest
import com.trenching.bot.console.application.ConsoleConfigValidator
import com.trenching.bot.console.domain.AgreedStrategyPreset
import com.trenching.bot.console.domain.ConfigErrorCode
import com.trenching.bot.console.domain.ConfigValidation
import com.trenching.bot.console.domain.NumberRange
import com.trenching.bot.console.domain.SimulationConfigLimits
import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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

    private fun config(body: String): SimulationConfig = assertIs<ConfigValidation.Valid>(validate(body)).config

    private fun invalid(body: String): ConfigValidation.Invalid = assertIs<ConfigValidation.Invalid>(validate(body))

    private fun fields(body: String): Map<String, String> = invalid(body).fields

    @Test
    fun `a missing config is the mvp configuration`() {
        assertEquals(SimulationConfig.mvp(), assertIs<ConfigValidation.Valid>(validate(null)).config)
    }

    @Test
    fun `an empty config falls back to the mvp value of every field`() {
        assertEquals(SimulationConfig.mvp(), config("{}"))
    }

    @Test
    fun `the default request spelled out equals the mvp configuration`() {
        val body = """{"notional":"0.1","maxAgeSlots":2,"dipBps":1500,"takeProfitBps":3000,"stopLossBps":1500,
            |"minMarketCapUsd":"10000","horizonSlots":4500,"latencySlots":1,"transactionCostSol":"0.001",
            |"quoteMint":"So11111111111111111111111111111111111111112"}""".trimMargin()

        assertEquals(SimulationConfig.mvp(), config(body))
    }

    @Test
    fun `edited values build the configuration through SimulationConfig of`() {
        val body = """{"notional":"25.5","maxAgeSlots":0,"dipBps":2000,"takeProfitBps":5000,"stopLossBps":1000,
            |"minMarketCapUsd":"25000.5","horizonSlots":900,"latencySlots":3,"transactionCostSol":"0.0025",
            |"quoteMint":"11111111111111111111111111111112"}""".trimMargin()

        val expected = SimulationConfig.of(BigDecimal("25.5"), 0, 2000, 5000, 1000, BigDecimal("25000.5"), 900, 3,
            BigDecimal("0.0025"), quoteMint = MintAddress.of("11111111111111111111111111111112"))
        assertEquals(expected, config(body))
    }

    @Test
    fun `every out-of-range field yields its own field error`() {
        val body = """{"notional":"0","maxAgeSlots":-1,"dipBps":0,"takeProfitBps":10000,"stopLossBps":0,
            |"minMarketCapUsd":"1000000001","horizonSlots":4501,"latencySlots":0,"transactionCostSol":"0.2",
            |"quoteMint":"not-a-mint"}""".trimMargin()

        assertEquals(setOf("notional", "maxAgeSlots", "dipBps", "takeProfitBps", "stopLossBps", "minMarketCapUsd", "horizonSlots",
            "latencySlots", "transactionCostSol", "quoteMint"), fields(body).keys)
    }

    @Test
    fun `the removed fee and slippage fields are rejected as unknown, each on its own field`() {
        assertEquals(mapOf("feeBps" to "is not a configuration field", "slippageBps" to "is not a configuration field"),
            fields("""{"feeBps":10,"slippageBps":50}"""))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "151", "1.5", "\"1\""])
    fun `a latency outside 1 to 150 whole slots is rejected on its field only`(value: String) {
        assertEquals(mapOf("latencySlots" to "must be an integer between 1 and 150"), fields("""{"latencySlots":$value}"""))
    }

    @Test
    fun `latency bounds are inclusive`() {
        assertEquals(150L, config("""{"latencySlots":150}""").latencySlots)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"0.100000000001\"", "\"-0.001\"", "0.001", "\"1e-3\""])
    fun `a transaction cost that is not a plain decimal string from 0 to 0,1 is rejected on its field only`(value: String) {
        assertEquals(setOf("transactionCostSol"), fields("""{"transactionCostSol":$value}""").keys)
    }

    @Test
    fun `a zero transaction cost and a zero minimum market cap are allowed`() {
        val config = config("""{"transactionCostSol":"0","minMarketCapUsd":"0"}""")
        assertEquals("0.000000000000", config.transactionCostSol.toPlainString())
        assertEquals("0.000000000000", config.minMarketCapUsd.toPlainString())
    }

    @Test
    fun `notional and minimum market cap as JSON numbers are rejected`() {
        assertEquals(setOf("notional", "minMarketCapUsd"), fields("""{"notional":100,"minMarketCapUsd":10000}""").keys)
    }

    @ParameterizedTest
    @ValueSource(strings = ["1e2", "1E2", "", " 100", "100.", ".5", "+100", "abc", "-5"])
    fun `notional that is not a positive plain decimal string is rejected`(notional: String) {
        assertEquals(setOf("notional"), fields("""{"notional":"$notional"}""").keys)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0.0000000004", "1.0000000005", "0.0000000000004"])
    fun `notional finer than one lamport is rejected`(notional: String) {
        assertEquals(mapOf("notional" to "must be a whole number of lamports: at most 9 decimals"),
            fields("""{"notional":"$notional"}"""))
    }

    @Test
    fun `notional of one lamport is allowed and trailing zeros do not count as decimals`() {
        assertEquals("0.000000001000", config("""{"notional":"0.000000001"}""").notional.toPlainString())
        assertEquals("1.000000000000", config("""{"notional":"1.000000000000"}""").notional.toPlainString())
    }

    @Test
    fun `notional beyond the stored precision is rejected`() {
        assertEquals(setOf("notional"), fields("""{"notional":"${"9".repeat(27)}"}""").keys)
        assertEquals(BigDecimal("9".repeat(26)), config("""{"notional":"${"9".repeat(26)}"}""").notional.stripTrailingZeros())
    }

    @ParameterizedTest
    @ValueSource(strings = ["10.5", "\"10\"", "true", "99999999999"])
    fun `a non-integer level value is rejected`(value: String) {
        assertEquals(setOf("dipBps"), fields("""{"dipBps":$value}""").keys)
    }

    @Test
    fun `the shared limits are exactly the bounds SimulationConfig of enforces`() {
        val limits = SimulationConfigLimits.CURRENT
        fun config(age: Long = 2, dip: Long = 1500, takeProfit: Long = 3000, stopLoss: Long = 1500, marketCap: Long = 10000,
                   horizon: Long = 4500, latency: Long = 1) =
            SimulationConfig.of(BigDecimal("0.1"), age, dip.toInt(), takeProfit.toInt(), stopLoss.toInt(), BigDecimal(marketCap), horizon,
                latency, BigDecimal("0.001"))
        val bounded = listOf<Pair<NumberRange, (Long) -> SimulationConfig>>(
            limits.maxAgeSlots to { config(age = it) },
            limits.dipBps to { config(dip = it) },
            limits.takeProfitBps to { config(takeProfit = it) },
            limits.stopLossBps to { config(stopLoss = it) },
            limits.minMarketCapUsd to { config(marketCap = it) },
            limits.horizonSlots to { config(horizon = it) },
            limits.latencySlots to { config(latency = it) },
            limits.dipWindowMs to { config().copyOf(dipWindowMs = it) },
            limits.maxHoldMs to { config().copyOf(maxHoldMs = it, hardExitStartMs = 240_000) },
            limits.hardExitStartMs to { config().copyOf(maxHoldMs = 180_000, hardExitStartMs = it) },
        )

        bounded.forEach { (range, build) ->
            val max = requireNotNull(range.max)
            build(range.min)
            build(max)
            assertFailsWith<IllegalArgumentException> { build(range.min - 1) }
            assertFailsWith<IllegalArgumentException> { build(max + 1) }
        }
        val cost = limits.transactionCostSol
        val unit = BigDecimal.ONE.movePointLeft(limits.transactionCostScale)
        fun withCost(value: BigDecimal) = SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1, value)
        withCost(cost.min)
        withCost(cost.max)
        assertFailsWith<IllegalArgumentException> { withCost(cost.min.subtract(unit)) }
        assertFailsWith<IllegalArgumentException> { withCost(cost.max.add(unit)) }
        fun withNotional(value: BigDecimal) = SimulationConfig.of(value, 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1, BigDecimal.ZERO)
        withNotional(BigDecimal.ONE.movePointLeft(limits.notionalScale))
        assertFailsWith<IllegalArgumentException> { withNotional(BigDecimal.ONE.movePointLeft(limits.notionalScale + 1)) }
    }

    @Test
    fun `the timed clocks are sent as whole milliseconds and the exit pair together`() {
        val config = config("""{"dipWindowMs":30000,"maxHoldMs":180000,"hardExitStartMs":240000}""")

        assertEquals(listOf(30_000L, 180_000L, 240_000L), listOf(config.dipWindowMs, config.maxHoldMs, config.hardExitStartMs))
        assertEquals(30_000L, config("""{"dipWindowMs":30000}""").dipWindowMs)
    }

    @Test
    fun `timed clocks sent as null are not set, which keeps the run untimed`() {
        assertEquals(SimulationConfig.mvp(), config("""{"dipWindowMs":null,"maxHoldMs":null,"hardExitStartMs":null}"""))
    }

    @Test
    fun `an exit clock without its pair is rejected on the missing field`() {
        assertEquals(mapOf("hardExitStartMs" to "must be set together with maxHoldMs"), fields("""{"maxHoldMs":180000}"""))
        assertEquals(mapOf("maxHoldMs" to "must be set together with hardExitStartMs"), fields("""{"hardExitStartMs":240000}"""))
    }

    @Test
    fun `an invalid exit clock is reported alone, not also as a missing pair`() {
        assertEquals(mapOf("maxHoldMs" to "must be an integer between 1000 and 3600000"), fields("""{"maxHoldMs":999}"""))
    }

    @ParameterizedTest
    @ValueSource(strings = ["999", "600001", "30000.5", "\"30000\"", "true"])
    fun `a dip window outside one second to ten minutes of whole milliseconds is rejected on its field only`(value: String) {
        assertEquals(mapOf("dipWindowMs" to "must be an integer between 1000 and 600000"), fields("""{"dipWindowMs":$value}"""))
    }

    @Test
    fun `exit clocks outside one second to an hour are rejected on each field`() {
        assertEquals(mapOf("maxHoldMs" to "must be an integer between 1000 and 3600000",
            "hardExitStartMs" to "must be an integer between 1000 and 3600000"),
            fields("""{"maxHoldMs":3600001,"hardExitStartMs":0}"""))
    }

    @Test
    fun `levels sent as percents build the same configuration as the same levels in basis points`() {
        val shared = """"notional":"0.1","minMarketCapUsd":"10000","latencySlots":1,"transactionCostSol":"0.001""""
        val clocks = """"dipWindowMs":30000,"maxHoldMs":180000,"hardExitStartMs":240000"""
        val percents = config("""{$shared,$clocks,"dipPercent":"20","takeProfitPercent":"55","stopLossPercent":"40"}""")

        assertEquals(config("""{$shared,$clocks,"dipBps":2000,"takeProfitBps":5500,"stopLossBps":4000}"""), percents)
        assertEquals(AgreedStrategyPreset.CONFIG, percents)
        assertEquals(1550, config("""{"dipPercent":"15.5"}""").dipBps)
        assertEquals(1, config("""{"stopLossPercent":"0.01"}""").stopLossBps)
    }

    @Test
    fun `a percent and basis points that agree are accepted together`() {
        assertEquals(2000, config("""{"dipBps":2000,"dipPercent":"20.00"}""").dipBps)
    }

    @Test
    fun `a percent and basis points that disagree are conflicting fields`() {
        val invalid = invalid("""{"dipBps":1500,"dipPercent":"20"}""")

        assertEquals(ConfigErrorCode.CONFLICTING_FIELDS, invalid.code)
        assertEquals(mapOf("dipPercent" to "must equal dipBps when both are sent: dipBps 1500 is 15 percent"), invalid.fields)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"20.255\"", "\"-1\"", "\"1e2\"", "\"\"", "20", "true"])
    fun `a percent that is not a decimal string with at most two decimals is an invalid percent on its field`(value: String) {
        val invalid = invalid("""{"dipPercent":$value}""")

        assertEquals(ConfigErrorCode.INVALID_PERCENT, invalid.code)
        assertEquals(mapOf("dipPercent" to "must be a percent string with at most 2 decimals, such as \"20\" or \"15.5\""), invalid.fields)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "100", "100.00"])
    fun `a percent outside the level range names the range`(value: String) {
        val invalid = invalid("""{"takeProfitPercent":"$value"}""")

        assertEquals(ConfigErrorCode.INVALID_PERCENT, invalid.code)
        assertEquals(mapOf("takeProfitPercent" to "must be between 0.01 and 99.99 percent"), invalid.fields)
    }

    @Test
    fun `an invalid percent next to another invalid field reports both under the percent code`() {
        val invalid = invalid("""{"stopLossPercent":"40.001","latencySlots":0}""")

        assertEquals(ConfigErrorCode.INVALID_PERCENT, invalid.code)
        assertEquals(setOf("stopLossPercent", "latencySlots"), invalid.fields.keys)
    }

    @Test
    fun `an invalid basis point value never also reports a conflict with its percent`() {
        val invalid = invalid("""{"dipBps":0,"dipPercent":"20"}""")

        assertEquals(ConfigErrorCode.CONFIG_INVALID, invalid.code)
        assertEquals(setOf("dipBps"), invalid.fields.keys)
    }

    @Test
    fun `a request with basis points only keeps the config invalid code`() {
        assertEquals(ConfigErrorCode.CONFIG_INVALID, invalid("""{"dipBps":0}""").code)
    }

    @Test
    fun `the range boundaries agree with SimulationConfig of`() {
        val body = """{"maxAgeSlots":1000,"dipBps":1,"takeProfitBps":9999,"stopLossBps":9999,"minMarketCapUsd":"1000000000",
            "horizonSlots":1,"latencySlots":150,"transactionCostSol":"0.1","dipWindowMs":600000,"maxHoldMs":1000,"hardExitStartMs":3600000}"""

        assertIs<ConfigValidation.Valid>(validate(body))
    }

    private fun SimulationConfig.copyOf(dipWindowMs: Long? = this.dipWindowMs, maxHoldMs: Long? = this.maxHoldMs,
                                        hardExitStartMs: Long? = this.hardExitStartMs): SimulationConfig =
        SimulationConfig.of(notional, maxAgeSlots, dipBps, takeProfitBps, stopLossBps, minMarketCapUsd, horizonSlots, latencySlots,
            transactionCostSol, quoteMint = quoteMint, dipWindowMs = dipWindowMs, maxHoldMs = maxHoldMs, hardExitStartMs = hardExitStartMs)
}
